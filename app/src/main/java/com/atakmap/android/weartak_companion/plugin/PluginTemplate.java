package com.atakmap.android.weartak_companion.plugin;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
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

public class PluginTemplate implements IPlugin {

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane templatePane;

    private static final String TAG = "WTK/Plugin";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WearTakBleClient bleClient;
    private BleCotBridge cotBridge;

    // ---------- UI (new) ----------
    private TextView connectionStatusTV;     // id: connection_status (chip)
    private Button scanButton;               // id: scanButton
    private RecyclerView deviceList;         // id: deviceList
    private TextView emptyState;             // id: emptyState

    private View connectedCard;              // id: connectedCard
    private TextView connectedDeviceTV;      // id: connectedDevice
    private TextView connectedMacTV;         // id: connectedMac

    private BleDeviceAdapter deviceAdapter;

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

    public PluginTemplate(IServiceController serviceController) {
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

        // Ensure we have a pluginContext
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

        // Lazily create BLE client once
        if (bleClient == null) {
            bleClient = new WearTakBleClient(pluginContext, new WearTakBleClient.StatusListener() {
                @Override
                public void onConnectionStatusChanged(final boolean connected) {
                    connectionStatus = connected ? "CONNECTED" : "DISCONNECTED";
                    mainHandler.post(() -> applyConnectionUiState(connected));
                }
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
                    if (connectionStatusTV != null) {
                        connectionStatusTV.setText("CONNECTED (READY)");
                        connectionStatusTV.setVisibility(View.VISIBLE);
                    }
                });
            }

            @Override
            public void onJson(String jsonLine) {
                Log.d(TAG, "RX JSON: " + jsonLine);

                if (cotBridge != null) {
                    try {
                        org.json.JSONObject env = new org.json.JSONObject(jsonLine);
                        String msgType = env.optString("msgType", "");
                        if ("watch_info".equals(msgType)) {
                            org.json.JSONObject p = env.optJSONObject("payload");
                            if (p != null) {
                                String pairedUid = p.optString("uid", "");
                                String cs = p.optString("cs", "");
                                Log.i(TAG, "PAIR ESTABLISHED uid=" + pairedUid + " cs=" + cs);

                                pluginContext.getSharedPreferences("wtk_pairing", Context.MODE_PRIVATE)
                                        .edit()
                                        .putString("paired_uid", pairedUid)
                                        .putString("paired_callsign", cs)
                                        .apply();
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "onJson: failed to parse envelope", t);
                    }

                    cotBridge.handleJsonFromWearTak(jsonLine);
                }
            }

            @Override
            public void onPaired(String deviceId, String callsign) {
                // optional: update UI if desired
            }

            @Override
            public void onError(String msg) {
                Log.w(TAG, "BLE JSON error: " + msg);
                mainHandler.post(() -> {
                    if (connectionStatusTV != null) {
                        connectionStatusTV.setText("BLE ERROR");
                        connectionStatusTV.setVisibility(View.VISIBLE);
                    }
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

            // ---- Bind UI ----
            connectionStatusTV = paneView.findViewById(R.id.connection_status);
            scanButton = paneView.findViewById(R.id.scanButton);

            deviceList = paneView.findViewById(R.id.deviceList);
            emptyState = paneView.findViewById(R.id.emptyState);

            connectedCard = paneView.findViewById(R.id.connectedCard);
            connectedDeviceTV = paneView.findViewById(R.id.connectedDevice);
            connectedMacTV = paneView.findViewById(R.id.connectedMac);

            if (connectionStatusTV != null) {
                connectionStatusTV.setText(connectionStatus);
                connectionStatusTV.setVisibility(View.VISIBLE);
            }

            // ---- Setup RecyclerView ----
            deviceList.setLayoutManager(new LinearLayoutManager(pluginContext));

            deviceAdapter = new BleDeviceAdapter(device -> {
                selectedDevice = device;

                Log.d(TAG, "UI connect clicked: " + device.address);

                if (bleClient != null) {
                    bleClient.resetSession("UI device selected " + device.address);
                    bleClient.connectToSelectedDevice(device);
                }

                if (connectionStatusTV != null) {
                    connectionStatusTV.setText("CONNECTING");
                    connectionStatusTV.setVisibility(View.VISIBLE);
                }

                if (deviceAdapter != null) {
                    deviceAdapter.setConnecting(device.address);
                }

                // Show connected card immediately with selected info (will become real CONNECTED on callback)
                if (connectedCard != null) connectedCard.setVisibility(View.VISIBLE);
                if (connectedDeviceTV != null) connectedDeviceTV.setText(safeName(device));
                if (connectedMacTV != null) connectedMacTV.setText(device.address);
            });

            deviceList.setAdapter(deviceAdapter);

            // ---- Scan button ----
            scanButton.setOnClickListener(v -> {
                int sessionId = ++scanSessionCount;
                LogX.i(TAG, "UI: Scan pressed. session=" + sessionId);

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

                if (connectionStatusTV != null) {
                    connectionStatusTV.setText("SCANNING");
                    connectionStatusTV.setVisibility(View.VISIBLE);
                }

                // optionally hide connected card when scanning
                if (connectedCard != null) connectedCard.setVisibility(View.GONE);

                scanForDevices(sessionId);
            });
        }

        if (!uiService.isPaneVisible(templatePane)) {
            uiService.showPane(templatePane, null);
        }
    }

    private void scanForDevices(final int sessionId) {
        if (bleClient == null) {
            LogX.w(TAG, "scanForDevices: bleClient is null. session=" + sessionId);
            return;
        }

        LogX.i(TAG, "scanForDevices: BEGIN session=" + sessionId);

        mainHandler.post(() -> {
            scannedDevices.clear();
            if (deviceAdapter != null) deviceAdapter.setDevices(scannedDevices);
            if (emptyState != null) emptyState.setVisibility(View.GONE);

            if (connectionStatusTV != null) {
                connectionStatusTV.setText("SCANNING");
                connectionStatusTV.setVisibility(View.VISIBLE);
            }
        });

        bleClient.scanForDevices(new WearTakBleClient.ScanListener() {
            @Override
            public void onDeviceFound(WearTakBleClient.DiscoveredDevice device) {
                LogX.d(TAG, "scanForDevices: FOUND session=" + sessionId
                        + " name=" + device.name
                        + " addr=" + device.address
                        + " rssi=" + device.rssi);

                mainHandler.post(() -> {
                    // upsert by address (your logic, kept)
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
                    if (emptyState != null) emptyState.setVisibility(scannedDevices.isEmpty() ? View.VISIBLE : View.GONE);

                    if (connectionStatusTV != null) {
                        connectionStatusTV.setText("READY");
                        connectionStatusTV.setVisibility(View.VISIBLE);
                    }
                });
            }

            @Override
            public void onScanError(String msg) {
                LogX.w(TAG, "scanForDevices: ERROR session=" + sessionId + " msg=" + msg);

                mainHandler.post(() -> {
                    if (connectionStatusTV != null) {
                        connectionStatusTV.setText("SCAN ERROR");
                        connectionStatusTV.setVisibility(View.VISIBLE);
                    }
                    if (emptyState != null) {
                        emptyState.setText("Scan error: " + msg);
                        emptyState.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private void applyConnectionUiState(boolean connected) {
        if (connectionStatusTV != null) {
            connectionStatusTV.setText(connected ? "CONNECTED" : "DISCONNECTED");
            connectionStatusTV.setVisibility(View.VISIBLE);
        }

        if (connected) {
            if (connectedCard != null) connectedCard.setVisibility(View.VISIBLE);

            if (selectedDevice != null) {
                if (connectedDeviceTV != null) connectedDeviceTV.setText(safeName(selectedDevice));
                if (connectedMacTV != null) connectedMacTV.setText(selectedDevice.address);
                if (deviceAdapter != null) deviceAdapter.setConnected(selectedDevice.address);
            }
        } else {
            // keep card visible or hide; your choice:
            // if (connectedCard != null) connectedCard.setVisibility(View.GONE);

            if (deviceAdapter != null) deviceAdapter.clearConnectionMarkers();
        }
    }

    private static String safeName(WearTakBleClient.DiscoveredDevice d) {
        if (d == null) return "—";
        if (d.name != null && !d.name.isEmpty()) return d.name;
        return "Unknown";
    }
}