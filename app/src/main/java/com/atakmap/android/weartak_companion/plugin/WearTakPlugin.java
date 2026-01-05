package com.atakmap.android.weartak_companion.plugin;

import android.annotation.SuppressLint;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

public class WearTakPlugin implements IPlugin {

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane templatePane;

    private static final String TAG = "WTK/Plugin";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WearTakBleClient bleClient;

    // Screen roots
    private View deviceScreenRoot;
    private View settingsScreenRoot;

    // Device screen
    private TextView connectionStatusTV;
    private Button scanButton;
    private RecyclerView deviceList;
    private TextView emptyState;

    private BleDeviceAdapter deviceAdapter;
    private final ArrayList<WearTakBleClient.DiscoveredDevice> scannedDevices = new ArrayList<>();
    private WearTakBleClient.DiscoveredDevice selectedDevice = null;

    // Settings screen
    private Button settingsBackButton;
    private TextView settingsDeviceSubtitle;

    private RecyclerView takServerListRV;
    private TakServerListAdapter takServerAdapter;

    private EditText takNameET;
    private EditText takAddressET;
    private EditText takPortET;
    private EditText takUsernameET;
    private EditText takPasswordET;

    private EditText reportIntervalET;

    private Button sendSettingsButton;
    private Button refreshSettingsButton;
    private TextView settingsStatusTV;

    // State
    private int scanSessionCount = 0;

    // last received snapshot
    private JSONObject lastSettingsPayload = null;
    private List<TakServerItem> currentServerList = new ArrayList<>();
    private int currentSelectedServerIndex = -1;
    private String currentConnectedAddress = null;

    public WearTakPlugin(IServiceController serviceController) {
        this.serviceController = serviceController;

        final PluginContextProvider ctxProvider = serviceController.getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }

        uiService = serviceController.getService(IHostUIService.class);
        MapView.getMapView();

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

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onStart() {
        if (uiService == null) return;
        uiService.addToolbarItem(toolbarItem);

        if (pluginContext == null && serviceController != null) {
            PluginContextProvider ctxProvider = serviceController.getService(PluginContextProvider.class);
            if (ctxProvider != null) {
                pluginContext = ctxProvider.getPluginContext();
                if (pluginContext != null) pluginContext.setTheme(R.style.ATAKPluginTheme);
            }
        }

        if (pluginContext == null) {
            Log.w(TAG, "onStart: pluginContext is null; skipping BLE init");
            return;
        }

        if (bleClient == null) {
            bleClient = new WearTakBleClient(pluginContext, connected -> {
                mainHandler.post(() -> {
                    if (connectionStatusTV != null) {
                        connectionStatusTV.setText(connected ? "CONNECTED" : "DISCONNECTED");
                    }
                    if (deviceAdapter != null) {
                        if (connected) {
                            if (currentConnectedAddress != null) {
                                deviceAdapter.setConnected(currentConnectedAddress);
                            } else {
                                deviceAdapter.notifyDataSetChanged();
                            }
                        } else {
                            deviceAdapter.clearConnectionMarkers();
                            currentConnectedAddress = null;
                        }
                    }
                });
            });
        }

        bleClient.start();

        bleClient.setJsonListener(new WearTakBleClient.JsonListener() {
            @Override
            public void onReady() {
                Log.i(TAG, "BLE READY");
                mainHandler.post(() -> {
                    if (connectionStatusTV != null) connectionStatusTV.setText("CONNECTED (READY)");
                    // auto-request settings when ready
                    requestSettingsFromWatch("onReady");
                });
            }

            @Override
            public void onJson(String jsonLine) {
                Log.d(TAG, "RX JSON: " + jsonLine);

                try {
                    JSONObject env = new JSONObject(jsonLine);
                    String msgType = env.optString("msgType", "");

                    // Watch -> plugin settings snapshot
                    if ("settings_request".equals(msgType)) {
                        JSONObject payload = env.optJSONObject("payload");
                        if (payload != null) {
                            lastSettingsPayload = payload;
                            currentServerList = parseTakServerList(payload);
                            Integer reportInt = optInt(payload, "reportIntSecs");

                            mainHandler.post(() -> {
                                if (settingsStatusTV != null) settingsStatusTV.setText("Settings loaded from watch.");
                                applyServersToUi(currentServerList);

                                if (reportInt != null && reportIntervalET != null) {
                                    reportIntervalET.setText(String.valueOf(reportInt));
                                }
                            });
                        }
                    }

                    // [Unverified] optional ack
                    if ("settings_set_ack".equals(msgType)) {
                        JSONObject p = env.optJSONObject("payload");
                        boolean ok = (p != null) && p.optBoolean("ok", false);
                        String err = (p != null) ? p.optString("error", "") : "";
                        mainHandler.post(() -> {
                            if (settingsStatusTV != null) {
                                settingsStatusTV.setText(ok ? "Watch accepted settings." : ("Watch rejected settings: " + err));
                            }
                        });
                    }

                } catch (Throwable t) {
                    Log.w(TAG, "onJson parse failed", t);
                }
            }

            @Override public void onPaired(String deviceId, String callsign) { }

            @Override
            public void onError(String msg) {
                mainHandler.post(() -> {
                    if (settingsStatusTV != null && settingsScreenRoot != null && settingsScreenRoot.getVisibility() == View.VISIBLE) {
                        settingsStatusTV.setText("BLE error: " + msg);
                    }
                });
            }
        });
    }

    @Override
    public void onStop() {
        if (uiService == null) return;
        uiService.removeToolbarItem(toolbarItem);
        if (bleClient != null) bleClient.stop();
    }

    private void showPane() {
        if (templatePane == null) {
            View paneView = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);

            templatePane = new PaneBuilder(paneView)
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();

            // roots
            deviceScreenRoot = paneView.findViewById(R.id.deviceScreenRoot);
            settingsScreenRoot = paneView.findViewById(R.id.settingsScreenRoot);

            // device screen
            connectionStatusTV = paneView.findViewById(R.id.connection_status);
            scanButton = paneView.findViewById(R.id.scanButton);
            deviceList = paneView.findViewById(R.id.deviceList);
            emptyState = paneView.findViewById(R.id.emptyState);

            deviceList.setLayoutManager(new LinearLayoutManager(pluginContext));
            deviceAdapter = new BleDeviceAdapter(new BleDeviceAdapter.Listener() {
                @Override
                public void onConnectClicked(WearTakBleClient.DiscoveredDevice device) {
                    selectedDevice = device;
                    if (bleClient != null) {
                        currentConnectedAddress = device.address;
                        bleClient.resetSession("UI connect " + device.address);
                        bleClient.connectToSelectedDevice(device);
                    }
                    if (deviceAdapter != null) deviceAdapter.setConnecting(device.address);
                }

                @Override
                public void onSettingsClicked(WearTakBleClient.DiscoveredDevice device) {
                    selectedDevice = device;
                    showSettingsScreen();
                    requestSettingsFromWatch("settingsClicked");
                    if (lastSettingsPayload != null) {
                        applyServersToUi(currentServerList);
                        Integer reportInt = optInt(lastSettingsPayload, "reportIntSecs");
                        if (reportInt != null && reportIntervalET != null) reportIntervalET.setText(String.valueOf(reportInt));
                    }
                }
            });
            deviceList.setAdapter(deviceAdapter);

            scanButton.setOnClickListener(v -> {
                int sessionId = ++scanSessionCount;
                if (bleClient != null) bleClient.resetSession("UI scan " + sessionId);

                scannedDevices.clear();
                if (deviceAdapter != null) {
                    deviceAdapter.setDevices(scannedDevices);
                    deviceAdapter.clearConnectionMarkers();
                }

                if (emptyState != null) emptyState.setVisibility(View.GONE);
                scanForDevices(sessionId);
            });

            // settings screen
            settingsBackButton = paneView.findViewById(R.id.settingsBackButton);
            settingsDeviceSubtitle = paneView.findViewById(R.id.settingsDeviceSubtitle);

            takServerListRV = paneView.findViewById(R.id.takServerList);
            takServerListRV.setLayoutManager(new LinearLayoutManager(pluginContext));

            takServerAdapter = new TakServerListAdapter(new TakServerListAdapter.Listener() {
                @Override
                public void onSelected(int index) {
                    currentSelectedServerIndex = index;
                    TakServerItem item = safeGet(currentServerList, index);
                    if (item != null) populateFieldsFromItem(item);
                }

                @Override
                public void onEnabledToggled(int index, boolean enabled) {
                    // Keep local list updated; selection stays
                    TakServerItem item = safeGet(currentServerList, index);
                    if (item != null) item.isEnabled = enabled;
                }
            });
            takServerListRV.setAdapter(takServerAdapter);

            takNameET = paneView.findViewById(R.id.takName);
            takAddressET = paneView.findViewById(R.id.takAddress);
            takPortET = paneView.findViewById(R.id.takPort);
            takUsernameET = paneView.findViewById(R.id.takUsername);
            takPasswordET = paneView.findViewById(R.id.takPassword);
            reportIntervalET = paneView.findViewById(R.id.reportInterval);

            refreshSettingsButton = paneView.findViewById(R.id.refreshSettingsButton);
            refreshSettingsButton.setOnClickListener(v -> requestSettingsFromWatch("refreshButton"));

            sendSettingsButton = paneView.findViewById(R.id.sendSettingsButton);
            settingsStatusTV = paneView.findViewById(R.id.settingsStatus);

            sendSettingsButton.setOnClickListener(v -> {
                // commit UI fields into selected item, then send whole list
                commitFieldsToSelectedItem();
                sendSettingsToWatch();
            });

            settingsBackButton.setOnClickListener(v -> showDeviceScreen());

            showDeviceScreen();
        }

        if (!uiService.isPaneVisible(templatePane)) {
            uiService.showPane(templatePane, null);
        }

        // request settings on open (if already connected)
        requestSettingsFromWatch("showPane");
    }

    private void scanForDevices(final int sessionId) {
        if (bleClient == null) return;

        bleClient.scanForDevices(new WearTakBleClient.ScanListener() {
            @Override
            public void onDeviceFound(WearTakBleClient.DiscoveredDevice device) {
                mainHandler.post(() -> {
                    upsertDevice(device);
                    if (deviceAdapter != null) deviceAdapter.setDevices(scannedDevices);
                });
            }

            @Override
            public void onScanFinished(List<WearTakBleClient.DiscoveredDevice> devices) {
                mainHandler.post(() -> {
                    if (emptyState != null) emptyState.setVisibility(scannedDevices.isEmpty() ? View.VISIBLE : View.GONE);
                });
            }

            @Override
            public void onScanError(String msg) {
                mainHandler.post(() -> {
                    if (emptyState != null) {
                        emptyState.setText("Scan error: " + msg);
                        emptyState.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private void upsertDevice(WearTakBleClient.DiscoveredDevice device) {
        int idx = -1;
        for (int i = 0; i < scannedDevices.size(); i++) {
            if (scannedDevices.get(i).address.equals(device.address)) { idx = i; break; }
        }
        if (idx >= 0) scannedDevices.set(idx, device);
        else scannedDevices.add(device);
    }

    // ---------- SETTINGS: request / parse / apply ----------

    private void requestSettingsFromWatch(String reason) {
        if (bleClient == null) return;
        // watch expects base.msg_type == "request_settings"
        String req = "{\"msg_type\":\"request_settings\"}";
        boolean ok = bleClient.writeJsonLineToWatch(req);

        Log.i(TAG, "request_settings (" + reason + ") ok=" + ok);

        if (settingsStatusTV != null && settingsScreenRoot != null && settingsScreenRoot.getVisibility() == View.VISIBLE) {
            settingsStatusTV.setText(ok ? "Requesting settings..." : "Cannot request settings (not connected).");
        }
    }

    private List<TakServerItem> parseTakServerList(JSONObject payload) {
        ArrayList<TakServerItem> out = new ArrayList<>();
        if (payload == null) return out;
        JSONArray arr = payload.optJSONArray("takServerList");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            out.add(TakServerItem.fromJson(o));
        }
        return out;
    }

    private void applyServersToUi(List<TakServerItem> servers) {
        if (takServerAdapter == null) return;

        takServerAdapter.setItems(servers);

        // pick selection: first enabled else first
        int sel = -1;
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).isEnabled) { sel = i; break; }
        }
        if (sel == -1 && !servers.isEmpty()) sel = 0;

        currentSelectedServerIndex = sel;
        takServerAdapter.setSelectedIndex(sel);

        TakServerItem chosen = safeGet(servers, sel);
        if (chosen != null) populateFieldsFromItem(chosen);
    }

    private void populateFieldsFromItem(TakServerItem item) {
        if (item == null) return;

        setText(takNameET, item.name);
        setText(takAddressET, item.address);
        setText(takPortET, String.valueOf(item.port));

        // For now: show username/pass only if not P12
        if (!item.isP12Cert) {
            setText(takUsernameET, item.username);
            setText(takPasswordET, item.password);
        } else {
            setText(takUsernameET, "");
            setText(takPasswordET, "");
        }
    }

    private void commitFieldsToSelectedItem() {
        TakServerItem item = safeGet(currentServerList, currentSelectedServerIndex);
        if (item == null) return;

        item.name = getText(takNameET);
        item.address = getText(takAddressET);

        Integer port = tryParseInt(getText(takPortET));
        if (port != null) item.port = port;

        if (!item.isP12Cert) {
            item.username = getText(takUsernameET);
            item.password = getText(takPasswordET);
        }
    }

    // ---------- SETTINGS: send back to watch ----------

    private void sendSettingsToWatch() {
        if (bleClient == null) return;

        // [Unverified] Proposed "set_settings" message contract
        JSONObject root = new JSONObject();
        JSONObject payload = new JSONObject();
        JSONArray list = new JSONArray();

        try {
            for (TakServerItem t : currentServerList) {
                list.put(t.toJson());
            }

            payload.put("takServerList", list);

            Integer reportInt = tryParseInt(getText(reportIntervalET));
            if (reportInt != null) payload.put("reportIntSecs", reportInt);

            root.put("msg_type", "set_settings");
            root.put("payload", payload);
        } catch (Throwable t) {
            if (settingsStatusTV != null) settingsStatusTV.setText("Failed to build settings JSON.");
            return;
        }

        boolean ok = bleClient.writeJsonLineToWatch(root.toString());
        if (settingsStatusTV != null) {
            settingsStatusTV.setText(ok ? "Sending settings to watch..." : "Failed to send (not connected).");
        }
    }

    // ---------- screen nav ----------

    private void showSettingsScreen() {
        if (settingsDeviceSubtitle != null) {
            String label = (selectedDevice != null)
                    ? ((selectedDevice.name == null ? "WearTAK" : selectedDevice.name) + " • " + selectedDevice.address)
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

    // ---------- utils ----------

    private static void setText(EditText e, String v) {
        if (e == null) return;
        e.setText(v == null ? "" : v);
    }

    private static String getText(EditText e) {
        if (e == null || e.getText() == null) return "";
        return e.getText().toString().trim();
    }

    private static Integer tryParseInt(String s) {
        try {
            if (s == null || s.isEmpty()) return null;
            return Integer.parseInt(s);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Integer optInt(JSONObject o, String key) {
        if (o == null) return null;
        if (!o.has(key)) return null;
        try { return o.getInt(key); } catch (Throwable t) { return null; }
    }

    private static TakServerItem safeGet(List<TakServerItem> list, int idx) {
        if (list == null) return null;
        if (idx < 0 || idx >= list.size()) return null;
        return list.get(idx);
    }
}