package com.atakmap.android.weartak_companion.plugin;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.coremap.maps.coords.GeoPoint;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

import java.util.List;

public class WearTakPlugin implements IPlugin {

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane templatePane;

    private static final String TAG = "WTK/Plugin";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WearTakBleClient bleClient;
    private BleCotBridge cotBridge;

    // ---------- Screen roots ----------
    private View deviceScreenRoot;
    private View settingsScreenRoot;

    // ---------- DEVICE SCREEN UI ----------
    private TextView connectionStatusTV;     // chip
    private Button scanButton;
    private RecyclerView deviceList;
    private TextView emptyState;

    private View connectedCard;
    private TextView connectedDeviceTV;
    private TextView connectedMacTV;
    private TextView connectedMetricsTV;

    private BleDeviceAdapter deviceAdapter;

    // ---------- SETTINGS SCREEN UI ----------
    private Button settingsBackButton;
    private TextView settingsDeviceSubtitle;

    private EditText takNameET;
    private EditText takAddressET;
    private EditText takPortET;
    private EditText takUsernameET;
    private EditText takPasswordET;

    private Button sendSettingsButton;
    private TextView settingsStatusTV;

    // ---------- State ----------
    private String connectionStatus = "DISCONNECTED";
    private int scanSessionCount = 0;

    private final java.util.ArrayList<WearTakBleClient.DiscoveredDevice> scannedDevices =
            new java.util.ArrayList<>();

    private WearTakBleClient.DiscoveredDevice selectedDevice = null;

    // ---------- Map stuff (unchanged / currently unused) ----------
    private MapView mapView;
    private Marker selfMarker;
    private GeoPoint selfGeoPoint;
    private String callsign;
    private String team;
    private Double selfLat;
    private Double selfLon;

    // ---------- UI status enum ----------
    private enum UiStatus {
        DISCONNECTED,
        READY,
        SCANNING,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    private UiStatus uiStatus = UiStatus.DISCONNECTED;

    public WearTakPlugin(IServiceController serviceController) {
        this.serviceController = serviceController;

        final PluginContextProvider ctxProvider =
                serviceController.getService(PluginContextProvider.class);

        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }

        uiService = serviceController.getService(IHostUIService.class);
        mapView = MapView.getMapView();

        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        pluginContext.getResources().getDrawable(R.drawable.ic_launcher),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        showPane();
                    }
                })
                .build();
    }

    @Override
    public void onStart() {
        Log.i(TAG, "PLUGIN onStart reached");
        if (uiService == null)
            return;

        uiService.addToolbarItem(toolbarItem);

        if (pluginContext == null && serviceController != null) {
            PluginContextProvider ctxProvider =
                    serviceController.getService(PluginContextProvider.class);
            if (ctxProvider != null) {
                pluginContext = ctxProvider.getPluginContext();
                if (pluginContext != null) {
                    pluginContext.setTheme(R.style.ATAKPluginTheme);
                }
            }
        }

        if (pluginContext == null) {
            Log.w(TAG, "onStart: pluginContext is null; skipping BLE initialization");
            return;
        }

        if (bleClient == null) {
            bleClient = new WearTakBleClient(pluginContext, connected -> {
                connectionStatus = connected ? "CONNECTED" : "DISCONNECTED";
                mainHandler.post(() -> applyConnectionUiState(connected));
            });
        }

        bleClient.start();

        if (cotBridge == null) {
            Log.d(TAG, "onStart: creating BleCotBridge instance");
            cotBridge = new BleCotBridge();
        }

        bleClient.setJsonListener(new WearTakBleClient.JsonListener() {
            @Override
            public void onReady() {
                Log.d(TAG, "BLE ready; JSON notifications enabled");
                mainHandler.post(() -> {
                    setUiStatus(UiStatus.CONNECTED); // show as connected/ready
                    if (connectionStatusTV != null) {
                        connectionStatusTV.setText("CONNECTED (READY)");
                    }
                });
            }

            @Override
            public void onJson(String jsonLine) {
                Log.d(TAG, "RX JSON: " + jsonLine);
                if (cotBridge != null) {
                    cotBridge.handleJsonFromWearTak(jsonLine);
                }

                // Stub: if you later parse metrics here (HR/bat/steps), update connectedMetricsTV
                // mainHandler.post(() -> connectedMetricsTV.setText("HR: 82   Bat: 100   Steps: 1200"));
            }

            @Override
            public void onPaired(String deviceId, String callsign) { }

            @Override
            public void onError(String msg) {
                Log.w(TAG, "BLE JSON error: " + msg);
                mainHandler.post(() -> {
                    setUiStatus(UiStatus.ERROR);
                    if (connectionStatusTV != null) connectionStatusTV.setText("BLE ERROR");
                });
            }
        });
    }

    @Override
    public void onStop() {
        if (uiService == null)
            return;

        uiService.removeToolbarItem(toolbarItem);

        if (bleClient != null) {
            bleClient.stop();
        }
    }

    private void showPane() {
        if (templatePane == null) {
            View paneView = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);

            templatePane = new PaneBuilder(paneView)
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();

            // ---- Screen roots ----
            deviceScreenRoot = paneView.findViewById(R.id.deviceScreenRoot);
            settingsScreenRoot = paneView.findViewById(R.id.settingsScreenRoot);

            // ---- Device screen bind ----
            connectionStatusTV = paneView.findViewById(R.id.connection_status);
            scanButton = paneView.findViewById(R.id.scanButton);
            deviceList = paneView.findViewById(R.id.deviceList);
            emptyState = paneView.findViewById(R.id.emptyState);

            connectedCard = paneView.findViewById(R.id.connectedCard);
            connectedDeviceTV = paneView.findViewById(R.id.connectedDevice);
            connectedMacTV = paneView.findViewById(R.id.connectedMac);
            connectedMetricsTV = paneView.findViewById(R.id.connectedMetrics);

            // ---- Settings screen bind ----
            settingsBackButton = paneView.findViewById(R.id.settingsBackButton);
            settingsDeviceSubtitle = paneView.findViewById(R.id.settingsDeviceSubtitle);

            takNameET = paneView.findViewById(R.id.takName);
            takAddressET = paneView.findViewById(R.id.takAddress);
            takPortET = paneView.findViewById(R.id.takPort);
            takUsernameET = paneView.findViewById(R.id.takUsername);
            takPasswordET = paneView.findViewById(R.id.takPassword);

            sendSettingsButton = paneView.findViewById(R.id.sendSettingsButton);
            settingsStatusTV = paneView.findViewById(R.id.settingsStatus);

            if (connectionStatusTV != null) {
                connectionStatusTV.setText(connectionStatus);
                connectionStatusTV.setVisibility(View.VISIBLE);
            }

            // ---- RecyclerView setup ----
            deviceList.setLayoutManager(new LinearLayoutManager(pluginContext));

            deviceAdapter = new BleDeviceAdapter(new BleDeviceAdapter.Listener() {
                @Override
                public void onConnectClicked(WearTakBleClient.DiscoveredDevice device) {
                    selectedDevice = device;

                    Log.d(TAG, "UI connect clicked: " + device.address);

                    setUiStatus(UiStatus.CONNECTING);

                    if (bleClient != null) {
                        bleClient.resetSession("UI device selected " + device.address);
                        bleClient.connectToSelectedDevice(device);
                    }

                    if (deviceAdapter != null) {
                        deviceAdapter.setConnecting(device.address);
                    }

                    if (connectedCard != null) connectedCard.setVisibility(View.VISIBLE);
                    if (connectedDeviceTV != null) connectedDeviceTV.setText(safeName(device));
                    if (connectedMacTV != null) connectedMacTV.setText(device.address);
                    if (connectedMetricsTV != null) connectedMetricsTV.setText("HR: —   Bat: —   Steps: —");
                }

                @Override
                public void onSettingsClicked(WearTakBleClient.DiscoveredDevice device) {
                    // Only meaningful when connected; adapter only offers it in that state
                    selectedDevice = device;
                    showSettingsScreen();
                }
            });

            deviceList.setAdapter(deviceAdapter);

            // ---- Scan button ----
            scanButton.setOnClickListener(v -> {
                int sessionId = ++scanSessionCount;
                LogX.i(TAG, "UI: Scan pressed. session=" + sessionId);

                setUiStatus(UiStatus.SCANNING);

                if (bleClient != null) {
                    bleClient.resetSession("UI Scan pressed session=" + sessionId);
                }

                scannedDevices.clear();
                selectedDevice = null;

                if (deviceAdapter != null) {
                    deviceAdapter.setDevices(scannedDevices);
                    deviceAdapter.clearConnectionMarkers();
                }

                if (emptyState != null) emptyState.setVisibility(View.GONE);
                if (connectedCard != null) connectedCard.setVisibility(View.GONE);

                scanForDevices(sessionId);
            });

            // ---- Settings back ----
            settingsBackButton.setOnClickListener(v -> showDeviceScreen());

            // ---- Send settings (stub) ----
            sendSettingsButton.setOnClickListener(v -> {
                // For now, just build a JSON and "pretend send".
                // Later this will call a write method on WearTakBleClient (requires A11C write path + protocol).
                String name = txt(takNameET);
                String addr = txt(takAddressET);
                String port = txt(takPortET);
                String user = txt(takUsernameET);
                String pass = txt(takPasswordET);

                String deviceId = (selectedDevice != null) ? selectedDevice.address : "—";

                String json = "{"
                        + "\"msgType\":\"set_tak_server\","
                        + "\"payload\":{"
                        + "\"device\":\"" + escape(deviceId) + "\","
                        + "\"name\":\"" + escape(name) + "\","
                        + "\"address\":\"" + escape(addr) + "\","
                        + "\"port\":" + safeInt(port, 0) + ","
                        + "\"username\":\"" + escape(user) + "\","
                        + "\"password\":\"" + escape(pass) + "\""
                        + "}"
                        + "}";

                Log.d(TAG, "SETTINGS STUB JSON -> " + json);

                if (settingsStatusTV != null) {
                    settingsStatusTV.setText("Stubbed send. (Not yet written to BLE)");
                }

                // TODO later:
                // bleClient.writeJson(json);
            });

            // Start on device screen
            showDeviceScreen();
            setUiStatus(UiStatus.DISCONNECTED);
        }

        if (!uiService.isPaneVisible(templatePane)) {
            uiService.showPane(templatePane, null);
        }
    }

    private void scanForDevices(final int sessionId) {
        if (bleClient == null) {
            LogX.w(TAG, "scanForDevices: bleClient is null. session=" + sessionId);
            setUiStatus(UiStatus.ERROR);
            return;
        }

        LogX.i(TAG, "scanForDevices: BEGIN session=" + sessionId);

        mainHandler.post(() -> {
            scannedDevices.clear();
            if (deviceAdapter != null) deviceAdapter.setDevices(scannedDevices);
            if (emptyState != null) emptyState.setVisibility(View.GONE);
        });

        bleClient.scanForDevices(new WearTakBleClient.ScanListener() {
            @Override
            public void onDeviceFound(WearTakBleClient.DiscoveredDevice device) {
                mainHandler.post(() -> {
                    int idx = -1;
                    for (int i = 0; i < scannedDevices.size(); i++) {
                        if (scannedDevices.get(i).address.equals(device.address)) {
                            idx = i;
                            break;
                        }
                    }
                    if (idx >= 0) scannedDevices.set(idx, device);
                    else scannedDevices.add(device);

                    if (deviceAdapter != null) deviceAdapter.setDevices(scannedDevices);
                    if (emptyState != null) emptyState.setVisibility(scannedDevices.isEmpty() ? View.VISIBLE : View.GONE);
                });
            }

            @Override
            public void onScanFinished(List<WearTakBleClient.DiscoveredDevice> devices) {
                mainHandler.post(() -> {
                    if (scannedDevices.isEmpty()) {
                        if (emptyState != null) emptyState.setVisibility(View.VISIBLE);
                    }
                    setUiStatus(UiStatus.READY);
                });
            }

            @Override
            public void onScanError(String msg) {
                LogX.w(TAG, "scanForDevices: ERROR session=" + sessionId + " msg=" + msg);
                mainHandler.post(() -> {
                    setUiStatus(UiStatus.ERROR);
                    if (emptyState != null) {
                        emptyState.setText("Scan error: " + msg);
                        emptyState.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    // -------------------- Screen nav --------------------

    private void showSettingsScreen() {
        if (settingsDeviceSubtitle != null) {
            String label = (selectedDevice != null)
                    ? (safeName(selectedDevice) + "  •  " + selectedDevice.address)
                    : "—";
            settingsDeviceSubtitle.setText(label);
        }
        if (deviceScreenRoot != null) deviceScreenRoot.setVisibility(View.GONE);
        if (settingsScreenRoot != null) settingsScreenRoot.setVisibility(View.VISIBLE);
    }

    private void showDeviceScreen() {
        if (settingsScreenRoot != null) settingsScreenRoot.setVisibility(View.GONE);
        if (deviceScreenRoot != null) deviceScreenRoot.setVisibility(View.VISIBLE);
    }

    // -------------------- Chip status styling --------------------

    private void setUiStatus(UiStatus s) {
        uiStatus = s;
        applyStatusChip();
    }

    private void applyConnectionUiState(boolean connected) {
        if (connected) {
            setUiStatus(UiStatus.CONNECTED);

            if (connectedCard != null) connectedCard.setVisibility(View.VISIBLE);
            if (selectedDevice != null) {
                if (connectedDeviceTV != null) connectedDeviceTV.setText(safeName(selectedDevice));
                if (connectedMacTV != null) connectedMacTV.setText(selectedDevice.address);

                if (deviceAdapter != null) deviceAdapter.setConnected(selectedDevice.address);
            }
        } else {
            setUiStatus(UiStatus.DISCONNECTED);
            if (deviceAdapter != null) deviceAdapter.clearConnectionMarkers();
        }
    }

    private void applyStatusChip() {
        if (connectionStatusTV == null) return;

        switch (uiStatus) {
            case CONNECTED:
                connectionStatusTV.setText("CONNECTED");
                connectionStatusTV.setBackgroundResource(R.drawable.bg_chip_success);
                break;
            case CONNECTING:
                connectionStatusTV.setText("CONNECTING");
                connectionStatusTV.setBackgroundResource(R.drawable.bg_chip_warning);
                break;
            case SCANNING:
                connectionStatusTV.setText("SCANNING");
                connectionStatusTV.setBackgroundResource(R.drawable.bg_chip_warning);
                break;
            case READY:
                connectionStatusTV.setText("READY");
                connectionStatusTV.setBackgroundResource(R.drawable.bg_chip_neutral);
                break;
            case ERROR:
                connectionStatusTV.setText("ERROR");
                connectionStatusTV.setBackgroundResource(R.drawable.bg_chip_error);
                break;
            case DISCONNECTED:
            default:
                connectionStatusTV.setText("DISCONNECTED");
                connectionStatusTV.setBackgroundResource(R.drawable.bg_chip_neutral);
                break;
        }

        connectionStatusTV.setVisibility(View.VISIBLE);
    }

    // -------------------- Helpers --------------------

    private static String safeName(WearTakBleClient.DiscoveredDevice d) {
        if (d == null) return "—";
        if (d.name != null && !d.name.isEmpty()) return d.name;
        return "Unknown";
    }

    private static String txt(EditText e) {
        if (e == null) return "";
        CharSequence cs = e.getText();
        return cs == null ? "" : cs.toString().trim();
    }

    private static int safeInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Throwable t) { return def; }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}