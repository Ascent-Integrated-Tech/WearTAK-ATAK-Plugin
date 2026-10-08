package com.weartak.atak.weartak_companion.plugin;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atak.plugins.impl.IToolbarItem;
import com.atakmap.android.gui.ImportFileBrowserDialog;
import com.atakmap.android.maps.MapView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.platform.ui.MotionEvent;

public class WearTakPlugin implements IPlugin, IToolbarItem {

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    Pane templatePane;

    private static final String TAG = "WTK/Plugin";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile Boolean lastSettingsApplyResult = null;

    private WearTakBleClient bleClient;
    private SystemBondedWatchConnection systemBondedConnection;
    private boolean systemBondedEnabled;
    private TextView systemBondedWatchStatus;
    private GarminConnectIqClient garminClient;
    private volatile boolean garminEnabled;
    private Button connectionModeButton;
    private TextView connectionModeAdvisory;
    private final SettingsSessionGuard settingsSession = new SettingsSessionGuard();
    private final ConnectionModeController connectionModes = new ConnectionModeController(
            new ConnectionModeController.Transport() {
                @Override public void stop() {
                    ++scanSessionCount;
                    systemBondedEnabled = garminEnabled = false;
                    if (systemBondedConnection != null) systemBondedConnection.stop();
                    if (bleClient != null) bleClient.stop();
                    if (garminClient != null) garminClient.stop();
                    systemBondedConnection = null;
                    bleClient = null;
                    garminClient = null;
                    currentConnectedAddress = null;
                    selectedDevice = null;
                    clearSessionSettings();
                    hideConnectedCard();
                    showDeviceScreen();
                    scannedDevices.clear();
                    if (deviceAdapter != null) {
                        deviceAdapter.setDevices(scannedDevices);
                        deviceAdapter.clearConnectionMarkers();
                    }
                }
                @Override public void start(ConnectionModeController.Mode mode, long token) {
                    if (mode == ConnectionModeController.Mode.GARMIN) startGarmin(token);
                    else initializeBleTransport(token);
                    updateConnectionModeUi();
                }
            });
    private BleCotBridge bleCotBridge;

    // ------------------ UI ROOT ------------------
    // IMPORTANT: This is the inflated main_layout.xml root view.
    private View paneView = null;

    // Screen roots
    private View deviceScreenRoot;
    private View settingsScreenRoot;

    // Device screen
    private TextView connectionStatusTV;
    private TextView garminConnectionStatusTV;
    private TextView garminConnectionBadgeTV;
    private Button garminToggleButton;
    private Button scanButton;
    private RecyclerView deviceList;
    private TextView emptyState;

    private BleDeviceAdapter deviceAdapter;
    private final ArrayList<WearTakBleClient.DiscoveredDevice> scannedDevices = new ArrayList<>();
    private WearTakBleClient.DiscoveredDevice selectedDevice = null;

    // Settings screen
    private Button settingsBackButton;
//    private TextView settingsDeviceSubtitle;

    private RecyclerView takServerListRV;
    private TakServerListAdapter takServerAdapter;

    private EditText takNameET;
    private EditText takAddressET;
    private EditText takPortET;
    private EditText takUsernameET;
    private EditText takPasswordET;
    private CheckBox takUseP12CB;
    private EditText takP12PasswordET;
    private TextView takP12StatusTV;
    private Button chooseP12Button;
    private Button clearP12Button;
    private EditText reportIntervalET;

    private Button sendSettingsButton;
    private Button refreshSettingsButton;
    private EditText watchCallsignET;
    private TextView settingsStatusTV;

    // State
    private int scanSessionCount = 0;

    // last received snapshot settings
    private JSONObject lastSettingsPayload = null;
    private List<TakServerItem> currentServerList = new ArrayList<>();
    private int currentSelectedServerIndex = -1;

    // Connection state
    private String currentConnectedAddress = null;
    private View connectedCardView = null;

    // Strings (keep literal for now; you can move to strings.xml later)
    private static final String SCAN_TEXT_DEFAULT = "Scan for WearTAK Devices";
    private static final String SCAN_TEXT_REFRESH = "Scan again to refresh devices";
    private static final String PREFS_NAME = "weartak_companion_prefs";
    private static final String KEY_PREF_ADDR = "preferred_device_address";
    private static final String KEY_PREF_NAME = "preferred_device_name";
    private static final String KEY_PREF_SET_AT = "preferred_device_set_at_ms";
    private static final String KEY_SYSTEM_BONDED_ENABLED = "use_system_bonded_watch";
    private static final String NO_P12_SELECTED = "No certificate selected.";
    private static final String WATCH_P12_SELECTED = "Certificate loaded from watch sync.";

    // ------------------ Connected card helpers ------------------

    private void showConnectedCard(WearTakBleClient.DiscoveredDevice d) {
        if (paneView == null) return;
        if (d == null) return;

        // already shown -> just rebind
        if (connectedCardView != null) {
            bindConnectedCard(connectedCardView, d);
            return;
        }

        ViewGroup deviceRoot = paneView.findViewById(R.id.deviceScreenRoot);
        Button scanBtn = paneView.findViewById(R.id.scanButton);
        if (deviceRoot == null || scanBtn == null) return;

        int scanIndex = deviceRoot.indexOfChild(scanBtn);
        if (scanIndex < 0) scanIndex = 0;

        connectedCardView = LayoutInflater.from(deviceRoot.getContext())
                .inflate(R.layout.item_ble_device, deviceRoot, false);

        // Insert above Scan button
        deviceRoot.addView(connectedCardView, scanIndex);

        bindConnectedCard(connectedCardView, d);
    }

    private void bindConnectedCard(View card, WearTakBleClient.DiscoveredDevice d) {
        if (card == null || d == null) return;

        TextView name = card.findViewById(R.id.deviceName);
        TextView mac = card.findViewById(R.id.deviceMac);
        TextView rssi = card.findViewById(R.id.rssiBadge);
        TextView state = card.findViewById(R.id.deviceState);

        Button disconnect = card.findViewById(R.id.disconnectButton);
        Button action = card.findViewById(R.id.actionButton);

        // Defensive: if you haven't updated item_ble_device.xml yet,
        // disconnectButton may not exist. Fail softly.
        if (name != null) name.setText((d.name != null && !d.name.isEmpty()) ? d.name : "Unknown");
        if (mac != null) mac.setText(d.address != null ? d.address : "—");
        if (rssi != null) rssi.setText(""); // avoid stale RSSI; can show last known if you want
        if (state != null) state.setText("Connected");

        if (disconnect != null) {
            disconnect.setVisibility(View.VISIBLE);
            disconnect.setEnabled(true);
            disconnect.setOnClickListener(v -> onDisconnectClicked());
        }

        if (action != null) {
            action.setText("Settings");
            action.setEnabled(true);
            action.setOnClickListener(v -> {
                if (bleClient == null || !bleClient.isConnected()) return;
                selectedDevice = d;
                showSettingsScreen();
                requestSettingsFromWatch("connectedCardSettings");
                if (lastSettingsPayload != null) {
                    applyServersToUi(currentServerList);
                    Integer reportInt = optInt(lastSettingsPayload, "reportIntSecs");
                    if (reportInt != null && reportIntervalET != null) {
                        reportIntervalET.setText(String.valueOf(reportInt));
                    }
                }
            });
        }
    }

    private void hideConnectedCard() {
        if (connectedCardView == null) return;
        ViewGroup parent = (ViewGroup) connectedCardView.getParent();
        if (parent != null) parent.removeView(connectedCardView);
        connectedCardView = null;
    }

    private void onDisconnectClicked() {
        if (bleClient == null) return;

        disableSystemBondedForManualUse();
        clearSessionSettings();
        ++scanSessionCount;
        try {
            bleClient.disconnect();
        } catch (Throwable t) {
            Log.w(TAG, "Disconnect failed (check WearTakBleClient API)", t);
        }
    }

    private void connectToDevice(WearTakBleClient.DiscoveredDevice device) {
        if (device == null || bleClient == null
                || connectionModes.getMode() != ConnectionModeController.Mode.TRADITIONAL) return;

        disableSystemBondedForManualUse();
        clearSessionSettings();
        ++scanSessionCount;
        selectedDevice = device;
        bleClient.resetSession("UI connect " + device.address);
        currentConnectedAddress = device.address;
        bleClient.connectToSelectedDevice(device);

        if (deviceAdapter != null) deviceAdapter.setConnecting(device.address);
        if (connectionStatusTV != null) connectionStatusTV.setText("Connecting WearTAK watch...");
    }

    // ------------------ Plugin lifecycle ------------------
    public WearTakPlugin(IServiceController serviceController) {
        this.serviceController = serviceController;

        final PluginContextProvider ctxProvider = serviceController.getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }

        uiService = serviceController.getService(IHostUIService.class);
        MapView mapView = MapView.getMapView();
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onStart() {
        if (Looper.myLooper() != mainHandler.getLooper()) {
            mainHandler.post(this::onStart);
            return;
        }
        this.serviceController.registerComponent(IToolbarItem.class, this);

        if (pluginContext == null && serviceController != null) {
            PluginContextProvider ctxProvider = serviceController.getService(PluginContextProvider.class);
            if (ctxProvider != null) {
                pluginContext = ctxProvider.getPluginContext();
                if (pluginContext != null) pluginContext.setTheme(R.style.ATAKPluginTheme);
            }
        }

        assert serviceController != null;
        uiService = serviceController.getService(IHostUIService.class); // safe refresh
        if (pluginContext == null) {
            Log.w(TAG, "onStart: pluginContext is null; skipping BLE init");
            return;
        }

        if (bleCotBridge == null) bleCotBridge = new BleCotBridge();
        SharedPreferences prefs = systemBondedPreferences();
        String saved = prefs.getString("connection_mode", null);
        if (saved == null) {
            saved = pluginContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString("connection_mode", null);
        }
        ConnectionModeController.Mode mode = ConnectionModeController.restore(saved,
                prefs.getBoolean(KEY_SYSTEM_BONDED_ENABLED, false));
        persistConnectionMode(mode);
        connectionModes.start(mode);
    }

    private void initializeBleTransport(final long token) {
        systemBondedEnabled = connectionModes.getMode() == ConnectionModeController.Mode.SAMSUNG;
        if (bleClient == null) {
            bleClient = new WearTakBleClient(pluginContext, connected -> {
                runOnMain(() -> {
                    if (!connectionModes.isCurrent(token)) return;
                    if (systemBondedConnection != null) systemBondedConnection.onConnectionChanged(connected);
                    if (connectionStatusTV != null) {
                        connectionStatusTV.setText(connected ? "CONNECTED"
                                : systemBondedConnection != null && systemBondedConnection.isActive()
                                ? systemBondedConnection.getStatus() : "DISCONNECTED");
                    }

                    if (connected) {
                        // Persist preferred device for future sessions
                        String addr = currentConnectedAddress;
                        String nm = (selectedDevice != null) ? selectedDevice.name : null;
                        if (addr != null && !addr.trim().isEmpty()) {
                            savePreferredDevice(addr, nm);
                        }
                        // Show the card if we have a selected device (common path).
                        if (selectedDevice != null) {
                            currentConnectedAddress = selectedDevice.address;
                            showConnectedCard(selectedDevice);
                        }
                    } else {
                        clearSessionSettings();
                        hideConnectedCard();
                        showDeviceScreen();
                        currentConnectedAddress = null;
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
                        }
                    }

                    refreshDeviceListUi();
                    updateSystemBondedControl();
                });
            });
        }

        if (bleCotBridge == null) {
            bleCotBridge = new BleCotBridge();
            Log.i(TAG, "BleCotBridge initialized in plugin");
        }

        bleClient.start();

        bleClient.setJsonListener(new WearTakBleClient.JsonListener() {
            @Override
            public void onReady() {
                Log.i(TAG, "BLE READY");
                runOnMain(() -> {
                    if (!connectionModes.isCurrent(token)) return;
                    if (!bleClient.isConnected()) return;
                    if (connectionStatusTV != null) connectionStatusTV.setText("CONNECTED (READY)");
                    requestSettingsFromWatch("onReady");
                });
            }

            @Override
            public void onJson(String jsonLine) {
                final long settingsToken = settingsSession.token();
                runOnMain(() -> {
                if (!connectionModes.isCurrent(token) || !settingsSession.isCurrent(settingsToken)) return;
                Log.d(TAG, "RX JSON bytes=" + jsonLine.getBytes(StandardCharsets.UTF_8).length);

                // CoT forwarder path (your design)
                if (bleCotBridge != null) {
                    try {
                        bleCotBridge.handleJsonFromWearTak(jsonLine);
                    } catch (Throwable t) {
                        Log.w(TAG, "BleCotBridge threw while handling RX JSON", t);
                    }
                }

                // Non-CoT messages handled locally (settings updates)
                try {
                    JSONObject env = new JSONObject(jsonLine);
                    String msgType = env.optString("msgType", "");

                    if ("settings_request".equals(msgType)) {
                        JSONObject payload = env.optJSONObject("payload");
                        if (payload != null) {
                            // treat it as “requested/current settings snapshot”
                            applySettingsPayloadDiscretely(payload, "settings_request", settingsToken);

                            mainHandler.post(() -> {
                                if (!connectionModes.isCurrent(token) || !settingsSession.isCurrent(settingsToken)) return;
                                if (settingsStatusTV != null && lastSettingsApplyResult == null) {
                                    settingsStatusTV.setText("Settings loaded from watch.");
                                }
                            });
                        }
                    }

                    if ("settings_set_ack".equals(msgType)) {
                        JSONObject p = env.optJSONObject("payload");
                        boolean ok = (p != null) && p.optBoolean("ok", false);
                        String err = (p != null) ? p.optString("error", "") : "";
                        lastSettingsApplyResult = ok;
                        mainHandler.post(() -> {
                            if (!connectionModes.isCurrent(token) || !settingsSession.isCurrent(settingsToken)) return;
                            if (settingsStatusTV != null) {
                                settingsStatusTV.setText(ok
                                        ? "Settings validated and saved on watch; server connection not yet verified."
                                        : ("Watch could not apply settings: " + err));
                            }
                        });
                    }

                    if ("settings_changed".equals(msgType)) {
                        JSONObject payload = env.optJSONObject("payload");
                        if (payload != null) {
                            applySettingsPayloadDiscretely(payload, "settings_changed", settingsToken);

                            mainHandler.post(() -> {
                                if (!connectionModes.isCurrent(token) || !settingsSession.isCurrent(settingsToken)) return;
                                if (settingsStatusTV != null) settingsStatusTV.setText("Settings were last changed from the watch.");
                            });
                        }
                    }

                } catch (Throwable t) {
                    Log.w(TAG, "onJson parse failed", t);
                }
                });
            }

            @Override public void onPaired(String deviceId, String callsign) { }

            @Override
            public void onTrustLost(String deviceAddress, String reason) {
                runOnMain(() -> {
                    if (!connectionModes.isCurrent(token)) return;
                    clearPreferredDevice(deviceAddress);
                    clearSessionSettings();
                    updateSystemBondedControl();
                    currentConnectedAddress = null;
                    hideConnectedCard();
                    if (connectionStatusTV != null) connectionStatusTV.setText("DISCONNECTED");
                    if (deviceAdapter != null) {
                        deviceAdapter.clearConnectionMarkers();
                        refreshDeviceListUi();
                    }
                    if (settingsStatusTV != null && settingsScreenRoot != null
                            && settingsScreenRoot.getVisibility() == View.VISIBLE) {
                        settingsStatusTV.setText("BLE trust lost. Re-pair required.");
                    }
                });
            }

            @Override
            public void onError(String msg) {
                runOnMain(() -> {
                    if (!connectionModes.isCurrent(token)) return;
                    if (systemBondedConnection != null) systemBondedConnection.onError(msg);
                    updateSystemBondedControl();
                    if (connectionStatusTV != null && !bleClient.isConnected()) {
                        connectionStatusTV.setText(
                                systemBondedConnection != null && systemBondedConnection.isActive()
                                        ? systemBondedConnection.getStatus() : "BLE: " + msg);
                    }
                    if (settingsStatusTV != null && settingsScreenRoot != null
                            && settingsScreenRoot.getVisibility() == View.VISIBLE) {
                        settingsStatusTV.setText("BLE error: " + msg);
                    }
                });
            }
        });
        if (systemBondedConnection == null) {
            systemBondedConnection = new SystemBondedWatchConnection(
                    new SystemBondedWatchConnection.Scheduler() {
                        @Override public void execute(Runnable action) { runOnMain(action); }
                        @Override public void later(Runnable action, long delayMs) {
                            mainHandler.postDelayed(action, delayMs);
                        }
                        @Override public void cancel(Runnable action) { mainHandler.removeCallbacks(action); }
                    },
                    new SystemBondedWatchConnection.Transport() {
                        @Override public WearTakBleClient.DiscoveredDevice knownBondedCandidate() {
                            return bleClient.getKnownBondedCompanion();
                        }
                        @Override public void find(WearTakBleClient.BondedCompanionListener listener) {
                            bleClient.findBondedCompanion(listener);
                        }
                        @Override public void connect(WearTakBleClient.DiscoveredDevice device) {
                            bleClient.connectToSelectedDevice(device, false);
                        }
                        @Override public void reset() { bleClient.resetSession("system bonded activation"); }
                    },
                    new SystemBondedWatchConnection.Listener() {
                        @Override public void onStatus(String message) {
                            if (!connectionModes.isCurrent(token)) return;
                            if (systemBondedWatchStatus != null) systemBondedWatchStatus.setText(message);
                            if (connectionStatusTV != null && systemBondedEnabled) {
                                connectionStatusTV.setText(message);
                            }
                            updateSystemBondedControl();
                        }
                        @Override public void onConnecting(WearTakBleClient.DiscoveredDevice device) {
                            if (!connectionModes.isCurrent(token)) return;
                            selectedDevice = device;
                            currentConnectedAddress = device.address;
                            if (deviceAdapter != null) deviceAdapter.setConnecting(device.address);
                        }
                        @Override public void onCandidate(WearTakBleClient.DiscoveredDevice device) {
                            if (!connectionModes.isCurrent(token)) return;
                            updateSystemBondedControl();
                        }
                    });
        }
        if (systemBondedEnabled) systemBondedConnection.start();
        updateSystemBondedControl();
    }

    private SharedPreferences systemBondedPreferences() {
        MapView mapView = MapView.getMapView();
        Context context = mapView != null ? mapView.getContext() : pluginContext;
        Context application = context.getApplicationContext();
        return (application != null ? application : context)
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void persistConnectionMode(ConnectionModeController.Mode mode) {
        systemBondedPreferences().edit().putString("connection_mode", mode.value)
                .putBoolean(KEY_SYSTEM_BONDED_ENABLED, mode == ConnectionModeController.Mode.SAMSUNG)
                .apply();
    }

    private void selectConnectionMode(ConnectionModeController.Mode mode) {
        persistConnectionMode(mode);
        connectionModes.select(mode);
        updateConnectionModeUi();
    }

    private void updateConnectionModeUi() {
        ConnectionModeController.Mode mode = connectionModes.getMode();
        if (connectionModeButton == null) return;
        connectionModeButton.setText(mode.label);
        connectionModeAdvisory.setText(mode == ConnectionModeController.Mode.SAMSUNG
                ? "Reuses the existing Galaxy Wearable bond; retries until you change mode or disconnect."
                : mode == ConnectionModeController.Mode.GARMIN
                ? "Requires Garmin Connect Mobile and WearTAK-Garmin with ATAK Relay enabled. BLE settings are unavailable."
                : "Scan and connect manually. Existing Bluetooth bonds are preserved.");
        scanButton.setVisibility(mode == ConnectionModeController.Mode.TRADITIONAL ? View.VISIBLE : View.GONE);
        deviceList.setVisibility(mode == ConnectionModeController.Mode.TRADITIONAL ? View.VISIBLE : View.GONE);
        emptyState.setVisibility(mode == ConnectionModeController.Mode.TRADITIONAL
                && scannedDevices.isEmpty() ? View.VISIBLE : View.GONE);
        systemBondedWatchStatus.setVisibility(mode == ConnectionModeController.Mode.SAMSUNG ? View.VISIBLE : View.GONE);
        paneView.findViewById(R.id.garminPanel).setVisibility(
                mode == ConnectionModeController.Mode.GARMIN ? View.VISIBLE : View.GONE);
        if (connectionStatusTV != null) {
            connectionStatusTV.setText(mode == ConnectionModeController.Mode.GARMIN
                    ? garminClient != null ? garminClient.getStatus() : "Garmin Connect IQ is off."
                    : bleClient != null && bleClient.isConnected()
                    ? "CONNECTED" : systemBondedEnabled ? systemBondedConnection.getStatus() : "DISCONNECTED");
        }
        updateSystemBondedControl();
    }

    private void showConnectionModeChooser() {
        ConnectionModeController.Mode[] modes = ConnectionModeController.Mode.values();
        String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; ++i) labels[i] = modes[i].label;
        new AlertDialog.Builder(pluginContext).setTitle("Connection mode")
                .setSingleChoiceItems(labels, connectionModes.getMode().ordinal(), (dialog, which) -> {
                    selectConnectionMode(modes[which]);
                    dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void disableSystemBondedForManualUse() {
        selectConnectionMode(ConnectionModeController.Mode.TRADITIONAL);
    }

    private void updateSystemBondedControl() {
        if (systemBondedWatchStatus == null || systemBondedConnection == null) return;
        WearTakBleClient.DiscoveredDevice candidate = bleClient.getKnownBondedCompanion();
        if (systemBondedWatchStatus != null) {
            systemBondedWatchStatus.setText(systemBondedConnection.getStatus()
                    + (candidate != null ? "\nBonded candidate: " + bleClient.describeBondedWatch(candidate.address)
                    : "\nNo verified compatible bond available; enable Bluetooth/Nearby Devices and run WearTAK to identify one."));
        }
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == mainHandler.getLooper()) action.run();
        else mainHandler.post(action);
    }

    @Override
    public void onStop() {
        runOnMain(() -> {
            connectionModes.stop();
        });
    }

    private void startGarmin(final long token) {
            garminEnabled = true;
                garminClient = new GarminConnectIqClient(pluginContext,
                        new GarminConnectIqClient.Listener() {
                            @Override
                            public void onStatusChanged(String status) {
                                mainHandler.post(() -> {
                                    if (!connectionModes.isCurrent(token)) return;
                                    if (garminConnectionStatusTV != null) {
                                        garminConnectionStatusTV.setText(status);
                                    }
                                    if (connectionStatusTV != null) connectionStatusTV.setText(status);
                                });
                            }

                            @Override
                            public void onConnectionChanged(boolean connected) {
                                mainHandler.post(() -> {
                                    if (connectionModes.isCurrent(token)) renderGarminConnection(connected);
                                });
                            }

                            @Override
                            public void onMessageReceived(JSONObject envelope) {
                                mainHandler.post(() -> {
                                    if (connectionModes.isCurrent(token)) handleGarminMessage(envelope);
                                });
                            }
                        });
            renderGarminConnecting();
            garminClient.start();
    }

    private void renderGarminConnection(boolean connected) {
        if (garminConnectionBadgeTV == null) return;
        garminConnectionBadgeTV.setText(connected ? "CONNECTED" : "NOT CONNECTED");
        garminConnectionBadgeTV.setBackgroundResource(
                connected ? R.drawable.bg_chip_success : R.drawable.bg_chip_warning);
    }

    private void renderGarminConnecting() {
        if (garminConnectionBadgeTV == null) return;
        garminConnectionBadgeTV.setText("CONNECTING");
        garminConnectionBadgeTV.setBackgroundResource(R.drawable.bg_chip_warning);
    }

    private void renderGarminOff() {
        if (garminConnectionBadgeTV == null) return;
        garminConnectionBadgeTV.setText("OFF");
        garminConnectionBadgeTV.setBackgroundResource(R.drawable.bg_chip_neutral);
    }

    private void handleGarminMessage(JSONObject envelope) {
        if (!garminEnabled) return;
        String msgType = envelope.optString("msgType", "");
        Log.i(TAG, "Garmin Connect IQ message: " + msgType);
        if (garminConnectionStatusTV != null) {
            garminConnectionStatusTV.setText("Garmin watch active (" + msgType + ").");
        }
        if ("entity_sync_request".equals(msgType)) {
            JSONObject requestPayload = envelope.optJSONObject("payload");
            int requestedLimit = requestPayload == null ? 50 : requestPayload.optInt("limit", 50);
            List<Map<String, Object>> entities = bleCotBridge == null
                    ? Collections.emptyList()
                    : bleCotBridge.getGarminEntitySnapshot(requestedLimit);
            Map<String, Object> responsePayload = new HashMap<>();
            responsePayload.put("entities", entities);
            if (garminClient == null || !garminClient.sendMessage("entities", responsePayload)) {
                Log.w(TAG, "Unable to send Garmin entity snapshot");
                if (garminConnectionStatusTV != null) {
                    garminConnectionStatusTV.setText("Garmin connected, but entity sync could not be sent.");
                }
            } else if (garminConnectionStatusTV != null) {
                garminConnectionStatusTV.setText("Garmin watch active: sending "
                        + entities.size() + " nearby unit(s).");
            }
            return;
        }
        if (bleCotBridge != null) {
            bleCotBridge.handleGarminMessage(envelope);
        }
    }

    // ------------------ UI build / pane ------------------

    private View addServerButton;
    private Button deleteServerButton;

    private void showPane() {
        if (templatePane == null) {
            // IMPORTANT: store paneView as a FIELD (not a local)
            paneView = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);

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
            garminConnectionStatusTV = paneView.findViewById(R.id.garminConnectionStatus);
            garminConnectionBadgeTV = paneView.findViewById(R.id.garminConnectionBadge);
            if (garminClient != null) {
                garminConnectionStatusTV.setText(garminClient.getStatus());
                if (garminEnabled) {
                    renderGarminConnection(garminClient.isConnected());
                } else {
                    renderGarminOff();
                }
            } else {
                garminConnectionStatusTV.setText("Garmin Connect IQ is off.");
                renderGarminOff();
            }
            garminToggleButton = paneView.findViewById(R.id.garminToggleButton);
            garminToggleButton.setText("Retry Garmin Connect IQ");
            garminToggleButton.setOnClickListener(v -> {
                connectionModes.restart();
            });
            scanButton = paneView.findViewById(R.id.scanButton);
            deviceList = paneView.findViewById(R.id.deviceList);
            emptyState = paneView.findViewById(R.id.emptyState);
            systemBondedWatchStatus = paneView.findViewById(R.id.systemBondedWatchStatus);
            connectionModeButton = paneView.findViewById(R.id.connectionModeButton);
            connectionModeAdvisory = paneView.findViewById(R.id.connectionModeAdvisory);
            connectionModeButton.setOnClickListener(v -> showConnectionModeChooser());

            deviceList.setLayoutManager(new LinearLayoutManager(pluginContext));
            deviceAdapter = new BleDeviceAdapter(new BleDeviceAdapter.Listener() {
                @Override
                public void onConnectClicked(WearTakBleClient.DiscoveredDevice device) {
                    connectToDevice(device);
                }

                @Override
                public void onSettingsClicked(WearTakBleClient.DiscoveredDevice device) {
                    if (bleClient == null || !bleClient.isConnected()) return;
                    selectedDevice = device;
                    showSettingsScreen();
                    requestSettingsFromWatch("settingsClicked");
                    if (lastSettingsPayload != null) {
                        applyServersToUi(currentServerList);
                        Integer reportInt = optInt(lastSettingsPayload, "reportIntSecs");
                        reportIntervalET = paneView.findViewById(R.id.reportInterval);

                        if (reportInt != null && reportIntervalET != null) reportIntervalET.setText(String.valueOf(reportInt));
                    }
                }
            });
            deviceList.setAdapter(deviceAdapter);
            updateConnectionModeUi();

            scanButton.setOnClickListener(v -> {
                if (connectionModes.getMode() != ConnectionModeController.Mode.TRADITIONAL) return;
                disableSystemBondedForManualUse();
                clearSessionSettings();
                int sessionId = ++scanSessionCount;
                if (bleClient != null) bleClient.resetSession("UI scan " + sessionId);

                scannedDevices.clear();
                if (deviceAdapter != null) {
                    deviceAdapter.setDevices(scannedDevices);
                    deviceAdapter.clearConnectionMarkers();
                }

                if (emptyState != null) emptyState.setVisibility(View.GONE);

                // scan text will update when scan finishes
                scanForDevices(sessionId);
            });

            // settings screen
            settingsBackButton = paneView.findViewById(R.id.settingsBackButton);
//            settingsDeviceSubtitle = paneView.findViewById(R.id.settingsDeviceSubtitle);
            watchCallsignET = paneView.findViewById(R.id.watchCallsign);

            takServerListRV = paneView.findViewById(R.id.takServerList);
            takServerListRV.setLayoutManager(new LinearLayoutManager(pluginContext));

            addServerButton = paneView.findViewById(R.id.addServerButton);

            addServerButton.setOnClickListener(v -> {
                addNewServer();
                applyServersToUi(currentServerList);
                currentSelectedServerIndex = currentServerList.size() - 1;
                if (takServerAdapter != null) takServerAdapter.setSelectedIndex(currentSelectedServerIndex);
                TakServerItem item = safeGet(currentServerList, currentSelectedServerIndex);
                if (item != null) populateFieldsFromItem(item);
            });

            takServerAdapter = new TakServerListAdapter(new TakServerListAdapter.Listener() {
                @Override
                public void onSelected(int index) {
                    currentSelectedServerIndex = index;
                    TakServerItem item = safeGet(currentServerList, index);
                    if (item != null) populateFieldsFromItem(item);
                }

                @Override
                public void onEnabledToggled(int index, boolean enabled) {
                    TakServerItem item = safeGet(currentServerList, index);
                    if (item != null) item.isEnabled = enabled;
                }

                @Override
                public void onDeleteClicked(int index) {
                    // Make sure edits in the text fields are not lost
                    commitFieldsToSelectedItem();

                    if (index < 0 || index >= currentServerList.size()) return;

                    currentServerList.remove(index);

                    // Select a reasonable next item
                    if (currentServerList.isEmpty()) {
                        currentSelectedServerIndex = -1;
                        applyServersToUi(currentServerList);
                        return;
                    }

                    int newSel = index;
                    if (newSel >= currentServerList.size()) newSel = currentServerList.size() - 1;

                    currentSelectedServerIndex = newSel;
                    applyServersToUi(currentServerList);
                    if (takServerAdapter != null) takServerAdapter.setSelectedIndex(newSel);

                    TakServerItem item = safeGet(currentServerList, newSel);
                    if (item != null) populateFieldsFromItem(item);

                    if (settingsStatusTV != null) settingsStatusTV.setText("Deleted server (not sent yet).");
                }

            });
            takServerListRV.setAdapter(takServerAdapter);

            takNameET = paneView.findViewById(R.id.takName);
            takAddressET = paneView.findViewById(R.id.takAddress);
            takPortET = paneView.findViewById(R.id.takPort);
            takUseP12CB = paneView.findViewById(R.id.takUseP12);
            takUsernameET = paneView.findViewById(R.id.takUsername);
            takPasswordET = paneView.findViewById(R.id.takPassword);
            takP12PasswordET = paneView.findViewById(R.id.takP12Password);
            takP12StatusTV = paneView.findViewById(R.id.takP12Status);
            chooseP12Button = paneView.findViewById(R.id.chooseP12Button);
            clearP12Button = paneView.findViewById(R.id.clearP12Button);
            reportIntervalET = paneView.findViewById(R.id.reportInterval);

            if (takUseP12CB != null) {
                takUseP12CB.setOnClickListener(v -> onAuthModeCheckboxClicked());
            }
            if (chooseP12Button != null) {
                chooseP12Button.setOnClickListener(v -> openP12Browser());
            }
            if (clearP12Button != null) {
                clearP12Button.setOnClickListener(v -> clearSelectedServerP12());
            }

            refreshSettingsButton = paneView.findViewById(R.id.refreshSettingsButton);
            refreshSettingsButton.setOnClickListener(v -> requestSettingsFromWatch("refreshButton"));

            sendSettingsButton = paneView.findViewById(R.id.sendSettingsButton);
            settingsStatusTV = paneView.findViewById(R.id.settingsStatus);

            sendSettingsButton.setOnClickListener(v -> {
                sendSettingsToWatch();
            });
            if (lastSettingsPayload != null) {
                applyServersToUi(currentServerList);
                setText(watchCallsignET, actualWatchCallsign);
                Integer report = optInt(lastSettingsPayload, "reportIntSecs");
                setText(reportIntervalET, report == null ? "" : String.valueOf(report));
            }
            updateSettingsSendState();

            settingsBackButton.setOnClickListener(v -> showDeviceScreen());

            showDeviceScreen();
        }

        if (uiService == null) return;

        if (!uiService.isPaneVisible(templatePane)) {
            uiService.showPane(templatePane, null);

            // only reset when opening
            resetDeviceScreenOnOpen();
            requestSettingsFromWatch("showPane");
        } else {
            uiService.closePane(templatePane);
        }
    }

    private void refreshDeviceListUi() {
        if (deviceAdapter == null) return;
        // Create a copy so we don't mutate scannedDevices ordering if you care
        ArrayList<WearTakBleClient.DiscoveredDevice> copy = new ArrayList<>();
        for (WearTakBleClient.DiscoveredDevice d : scannedDevices) {
            if (d == null || d.address == null) continue;
            if (currentConnectedAddress != null && currentConnectedAddress.equals(d.address)) continue;
            copy.add(d);
        }

        Collections.sort(copy, (a, b) -> {
            // 1) connected first
            boolean aConn = a.address != null && a.address.equals(currentConnectedAddress);
            boolean bConn = b.address != null && b.address.equals(currentConnectedAddress);
            if (aConn != bConn) return aConn ? -1 : 1;

            // 2) preferred next
            String pref = loadPreferredAddress(); // from prefs
            boolean aPref = pref != null && pref.equals(a.address);
            boolean bPref = pref != null && pref.equals(b.address);
            if (aPref != bPref) return aPref ? -1 : 1;

            // 3) name then address (stable)
            String an = a.name == null ? "" : a.name;
            String bn = b.name == null ? "" : b.name;
            int c = an.compareToIgnoreCase(bn);
            if (c != 0) return c;

            String aa = a.address == null ? "" : a.address;
            String ba = b.address == null ? "" : b.address;
            return aa.compareToIgnoreCase(ba);
        });

        deviceAdapter.setDevices(copy);
    }

    private void resetDeviceScreenOnOpen() {
        // Clear scan list every time dropdown is opened
        scannedDevices.clear();
        if (deviceAdapter != null) deviceAdapter.setDevices(scannedDevices);

        if (emptyState != null) {
            emptyState.setText("No devices yet. Tap Scan.");
            emptyState.setVisibility(View.VISIBLE);
        }

        if (scanButton != null) scanButton.setText(SCAN_TEXT_DEFAULT);
        if (connectionStatusTV != null) {
            connectionStatusTV.setText(bleClient != null && bleClient.isConnected()
                    ? "CONNECTED (READY)"
                    : systemBondedConnection != null && systemBondedEnabled
                    ? systemBondedConnection.getStatus() : "DISCONNECTED");
        }
        updateSystemBondedControl();

        // Connected card: show only if we have a selectedDevice from this session
        if (bleClient != null && bleClient.isConnected()
                && selectedDevice != null && currentConnectedAddress != null
                && currentConnectedAddress.equals(selectedDevice.address)) {
            showConnectedCard(selectedDevice);
        } else {
            hideConnectedCard();
        }
        updateConnectionModeUi();
    }

    private void scanForDevices(final int sessionId) {
        if (bleClient == null) return;

        bleClient.scanForDevices(new WearTakBleClient.ScanListener() {
            @Override
            public void onDeviceFound(WearTakBleClient.DiscoveredDevice device) {
                mainHandler.post(() -> {
                    if (sessionId != scanSessionCount) return;
                    upsertDevice(device);
                    refreshDeviceListUi();
                });
            }

            @Override
            public void onScanFinished(List<WearTakBleClient.DiscoveredDevice> devices) {
                mainHandler.post(() -> {
                    if (sessionId != scanSessionCount) return;
                    if (emptyState != null) {
                        emptyState.setVisibility(scannedDevices.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                    if (scanButton != null) {
                        scanButton.setText(SCAN_TEXT_REFRESH);
                    }
                });
            }

            @Override
            public void onScanError(String msg) {
                mainHandler.post(() -> {
                    if (sessionId != scanSessionCount) return;
                    if (emptyState != null) {
                        emptyState.setText("Scan error: " + msg);
                        emptyState.setVisibility(View.VISIBLE);
                    }
                    if (scanButton != null) {
                        scanButton.setText(SCAN_TEXT_REFRESH);
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
        if (bleClient == null || !bleClient.isConnected()) return;
        lastSettingsApplyResult = null;
        String req = "{\"msg_type\":\"request_settings\"}";
        boolean ok = bleClient.writeJsonLineToWatch(req);

        Log.i(TAG, "request_settings (" + reason + ") ok=" + ok);

        if (settingsStatusTV != null && settingsScreenRoot != null
                && settingsScreenRoot.getVisibility() == View.VISIBLE) {
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

        int sel = -1;
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).isEnabled) { sel = i; break; }
        }
        if (sel == -1 && !servers.isEmpty()) sel = 0;

        currentSelectedServerIndex = sel;
        takServerAdapter.setSelectedIndex(sel);

        TakServerItem chosen = safeGet(servers, sel);
        if (chosen != null) populateFieldsFromItem(chosen);
        else clearServerEditor();
    }

    private void populateFieldsFromItem(TakServerItem item) {
        if (item == null) return;

        setText(takNameET, item.name);
        setText(takAddressET, item.address);
        setText(takPortET, String.valueOf(item.port));
        if (takUseP12CB != null) takUseP12CB.setChecked(item.isP12Cert);

        if (!item.isP12Cert) {
            setText(takUsernameET, item.username);
            setText(takPasswordET, item.password);
            setText(takP12PasswordET, "");
        } else {
            item.p12CertPassword = TakServerItem.normalizeP12Password(item.p12CertPassword);
            setText(takUsernameET, "");
            setText(takPasswordET, "");
            setText(takP12PasswordET, item.p12CertPassword);
        }

        refreshSelectedServerAuthUi(item);
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
        } else {
            item.username = "";
            item.password = "";
            item.p12CertPassword = TakServerItem.normalizeP12Password(getText(takP12PasswordET));
        }
    }

    public String desiredWatchCallsign = null;
    public String actualWatchCallsign = null;

    // ---------- SETTINGS: send back to watch ----------
    private void sendSettingsToWatch() {
        if (bleClient == null || !bleClient.isConnected()) return;
        if (!settingsSession.canSend()) {
            if (settingsStatusTV != null) settingsStatusTV.setText("Wait for this watch's settings snapshot before sending.");
            return;
        }
        commitFieldsToSelectedItem();

        JSONObject root = new JSONObject();
        JSONObject payload = new JSONObject();
        JSONArray list = new JSONArray();

        try {
            for (int i = 0; i < currentServerList.size(); i++) {
                TakServerItem t = currentServerList.get(i);
                if (t.isP12Cert && (t.p12Cert == null || t.p12Cert.trim().isEmpty())) {
                    if (settingsStatusTV != null) {
                        String name = (t.name == null || t.name.trim().isEmpty()) ? ("Server " + (i + 1)) : t.name;
                        settingsStatusTV.setText(name + " is set to P12 auth but has no certificate selected.");
                    }
                    return;
                }
            }

            for (TakServerItem t : currentServerList) {
                list.put(t.toJson());
            }

            payload.put("takServerList", list);

            Integer reportInt = tryParseInt(getText(reportIntervalET));
            if (reportInt != null) payload.put("reportIntSecs", reportInt);

            String cs = getText(watchCallsignET);
            if (!cs.equals(actualWatchCallsign)) {
                payload.put("callsign", cs);
                desiredWatchCallsign = cs;
            }

            root.put("msg_type", "set_settings");
            root.put("payload", payload);
        } catch (Throwable t) {
            if (settingsStatusTV != null) settingsStatusTV.setText("Failed to build settings JSON.");
            return;
        }

        String jsonLine = root.toString().replace("\\/", "/");
        lastSettingsApplyResult = null;
        boolean ok = bleClient.writeJsonLineToWatch(jsonLine);
        if (settingsStatusTV != null) {
            settingsStatusTV.setText(ok ? "Sending settings to watch..." : "Failed to send (not connected).");
        }
    }

    private void addNewServer() {
        commitFieldsToSelectedItem();

        TakServerItem item = new TakServerItem();
        item.isEnabled = false;
        item.name = "New Server";
        item.address = "";
        item.port = 8089;

        item.isP12Cert = false;
        item.username = "";
        item.password = "";
        item.p12Cert = "";
        item.p12CertPassword = "";
        item.p12DisplayName = null;

        currentServerList.add(item);

        int newIndex = currentServerList.size() - 1;

        if (takServerAdapter != null) {
            takServerAdapter.setItems(currentServerList);
            takServerAdapter.setSelectedIndex(newIndex);
        }
        currentSelectedServerIndex = newIndex;

        populateFieldsFromItem(item);

        if (settingsStatusTV != null) settingsStatusTV.setText("Added server (not sent yet).");
    }

    private void deleteSelectedServer() {
        int idx = currentSelectedServerIndex;

        if (idx < 0 || idx >= currentServerList.size()) {
            if (settingsStatusTV != null) settingsStatusTV.setText("No server selected to delete.");
            return;
        }

        currentServerList.remove(idx);

        if (currentServerList.isEmpty()) {
            currentSelectedServerIndex = -1;

            if (takServerAdapter != null) {
                takServerAdapter.setItems(currentServerList);
                takServerAdapter.setSelectedIndex(-1);
            }

            setText(takNameET, "");
            setText(takAddressET, "");
            setText(takPortET, "");
            if (takUseP12CB != null) takUseP12CB.setChecked(false);
            setText(takUsernameET, "");
            setText(takPasswordET, "");
            setText(takP12PasswordET, "");
            updateP12Status(null);
            refreshSelectedServerAuthUi(null);

            if (settingsStatusTV != null) settingsStatusTV.setText("Deleted server (not sent yet).");
            return;
        }

        if (idx >= currentServerList.size()) idx = currentServerList.size() - 1;

        currentSelectedServerIndex = idx;
        TakServerItem item = currentServerList.get(idx);

        if (takServerAdapter != null) {
            takServerAdapter.setItems(currentServerList);
            takServerAdapter.setSelectedIndex(idx);
        }

        populateFieldsFromItem(item);

        if (settingsStatusTV != null) settingsStatusTV.setText("Deleted server (not sent yet).");
    }

    // ---------- screen nav ----------

    private void showSettingsScreen() {
        if (bleClient == null || !bleClient.isConnected()) return;
        updateSettingsSendState();
//        if (settingsDeviceSubtitle != null) {
//            String label = (selectedDevice != null)
//                    ? ((selectedDevice.name == null ? "WearTAK" : selectedDevice.name) + " • " + selectedDevice.address)
//                    : "—";
//            settingsDeviceSubtitle.setText(label);
//        }
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

    private static String optStringOrNull(JSONObject o, String key) {
        if (o == null) return null;
        if (!o.has(key)) return null;
        String v = o.optString(key, null);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    private static boolean hasArray(JSONObject o, String key) {
        return o != null && o.optJSONArray(key) != null;
    }

    private static TakServerItem safeGet(List<TakServerItem> list, int idx) {
        if (list == null) return null;
        if (idx < 0 || idx >= list.size()) return null;
        return list.get(idx);
    }

    private void clearServerEditor() {
        setText(takNameET, "");
        setText(takAddressET, "");
        setText(takPortET, "");
        if (takUseP12CB != null) takUseP12CB.setChecked(false);
        setText(takUsernameET, "");
        setText(takPasswordET, "");
        setText(takP12PasswordET, "");
        updateP12Status(null);
        refreshSelectedServerAuthUi(null);
    }

    private void onAuthModeCheckboxClicked() {
        TakServerItem item = safeGet(currentServerList, currentSelectedServerIndex);
        if (item == null || takUseP12CB == null) {
            if (takUseP12CB != null) takUseP12CB.setChecked(false);
            return;
        }

        boolean useP12 = takUseP12CB.isChecked();
        item.isP12Cert = useP12;
        if (useP12) {
            item.username = "";
            item.password = "";
            item.p12CertPassword = TakServerItem.normalizeP12Password(item.p12CertPassword);
            setText(takP12PasswordET, item.p12CertPassword);
        } else {
            item.p12Cert = "";
            item.p12CertPassword = "";
            item.p12DisplayName = null;
            setText(takP12PasswordET, "");
        }

        refreshSelectedServerAuthUi(item);
        if (takServerAdapter != null) takServerAdapter.notifyDataSetChanged();
    }

    private void refreshSelectedServerAuthUi(TakServerItem item) {
        boolean useP12 = item != null && item.isP12Cert;

        setVisible(takUsernameET, !useP12);
        setVisible(takPasswordET, !useP12);
        setVisible(takP12StatusTV, useP12);
        setVisible(takP12PasswordET, useP12);
        View p12Buttons = paneView == null ? null : paneView.findViewById(R.id.takP12Buttons);
        setVisible(p12Buttons, useP12);

        if (chooseP12Button != null) {
            boolean hasP12 = item != null && item.p12Cert != null && !item.p12Cert.isEmpty();
            chooseP12Button.setText(hasP12 ? "Replace P12 File" : "Choose P12 File");
        }
        updateP12Status(item);
    }

    private void updateP12Status(TakServerItem item) {
        if (takP12StatusTV == null) return;
        if (item == null || !item.isP12Cert) {
            takP12StatusTV.setText(NO_P12_SELECTED);
            return;
        }

        if (item.p12DisplayName != null && !item.p12DisplayName.trim().isEmpty()) {
            takP12StatusTV.setText("Selected: " + item.p12DisplayName);
            return;
        }

        if (item.p12Cert != null && !item.p12Cert.isEmpty()) {
            takP12StatusTV.setText(WATCH_P12_SELECTED);
            return;
        }

        takP12StatusTV.setText(NO_P12_SELECTED);
    }

    private void openP12Browser() {
        TakServerItem item = safeGet(currentServerList, currentSelectedServerIndex);
        if (item == null) {
            if (settingsStatusTV != null) settingsStatusTV.setText("Select a server before choosing a P12 file.");
            return;
        }

        final long settingsToken = settingsSession.token();
        ImportFileBrowserDialog browser = new ImportFileBrowserDialog(MapView.getMapView().getContext());
        browser.setExtensionTypes("p12");
        browser.setTitle("Select P12 Certificate");
        browser.setOnDismissListener(new ImportFileBrowserDialog.DialogDismissed() {
            @Override
            public void onFileSelected(File file) {
                runOnMain(() -> {
                    if (!settingsSession.isCurrent(settingsToken)
                            || safeGet(currentServerList, currentSelectedServerIndex) != item) return;
                    importP12File(file);
                });
            }

            @Override
            public void onDialogClosed() {
            }
        });
        browser.show();
    }

    private void importP12File(File file) {
        TakServerItem item = safeGet(currentServerList, currentSelectedServerIndex);
        if (item == null || file == null) return;

        try {
            byte[] bytes = readFileBytes(file);
            if (bytes.length == 0) {
                if (settingsStatusTV != null) settingsStatusTV.setText("Selected P12 file was empty.");
                return;
            }

            item.isP12Cert = true;
            item.username = "";
            item.password = "";
            item.p12Cert = TakServerItem.normalizeP12Cert(Base64.encodeToString(bytes, Base64.NO_WRAP));
            item.p12CertPassword = TakServerItem.normalizeP12Password(getText(takP12PasswordET));
            item.p12DisplayName = file.getName();

            if (takUseP12CB != null) takUseP12CB.setChecked(true);
            setText(takP12PasswordET, item.p12CertPassword);
            refreshSelectedServerAuthUi(item);
            if (takServerAdapter != null) takServerAdapter.notifyDataSetChanged();
            if (settingsStatusTV != null) settingsStatusTV.setText("Loaded " + file.getName() + " for the selected server.");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to load p12 file", t);
            if (settingsStatusTV != null) settingsStatusTV.setText("Failed to read the selected P12 file.");
        }
    }

    private void clearSelectedServerP12() {
        TakServerItem item = safeGet(currentServerList, currentSelectedServerIndex);
        if (item == null) return;

        item.p12Cert = "";
        item.p12CertPassword = "";
        item.p12DisplayName = null;
        setText(takP12PasswordET, "");
        refreshSelectedServerAuthUi(item);
        if (takServerAdapter != null) takServerAdapter.notifyDataSetChanged();
        if (settingsStatusTV != null) settingsStatusTV.setText("Cleared the selected server certificate.");
    }

    private static byte[] readFileBytes(File file) throws Exception {
        FileInputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static void setVisible(View view, boolean visible) {
        if (view == null) return;
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void updateConnectedCardTitleFromCallsign(String callsign) {
        if (connectedCardView == null) return;
        if (callsign == null || callsign.trim().isEmpty()) return;

        TextView name = connectedCardView.findViewById(R.id.deviceName);
        if (name != null) name.setText(callsign);
    }

    private void clearSessionSettings() {
        settingsSession.clear();
        lastSettingsPayload = null;
        lastSettingsApplyResult = null;
        currentServerList = new ArrayList<>();
        currentSelectedServerIndex = -1;
        actualWatchCallsign = desiredWatchCallsign = null;
        if (takServerAdapter != null) {
            takServerAdapter.setItems(currentServerList);
            takServerAdapter.setSelectedIndex(-1);
        }
        clearServerEditor();
        setText(watchCallsignET, "");
        setText(reportIntervalET, "");
        if (settingsStatusTV != null) settingsStatusTV.setText("Waiting for this watch's settings snapshot.");
        updateSettingsSendState();
    }

    private void updateSettingsSendState() {
        if (sendSettingsButton != null) {
            sendSettingsButton.setEnabled(settingsSession.canSend()
                    && bleClient != null && bleClient.isConnected());
        }
    }

    private void applySettingsPayloadDiscretely(JSONObject payload, String reason, long settingsToken) {
        if (payload == null) return;
        boolean complete = payload.optJSONArray("takServerList") != null
                && optInt(payload, "reportIntSecs") != null;
        if (!settingsSession.acceptSnapshot(settingsToken, complete)) return;
        if (complete) lastSettingsPayload = payload;
        JSONArray servers = payload.optJSONArray("takServerList");
        Log.i(TAG, "applySettingsPayloadDiscretely reason=" + reason
                + " serverCount=" + (servers == null ? 0 : servers.length()));

        // callsign (device-wide)
        if (payload.has("callsign")) {
            String cs = payload.optString("callsign", "").trim();
            if (!cs.isEmpty()) {
                actualWatchCallsign = cs;
                runOnMain(() -> {
                    if (watchCallsignET != null) watchCallsignET.setText(cs);
                    updateConnectedCardTitleFromCallsign(cs); // optional if you already have this
                });
            }
        }

        // reporting interval (device-wide)
        if (payload.has("reportIntSecs")) {
            Integer reportInt = optInt(payload, "reportIntSecs");
            if (reportInt != null) {
                runOnMain(() -> {
                    if (reportIntervalET != null) reportIntervalET.setText(String.valueOf(reportInt));
                });
            }
        }

        // tak servers (list)
        if (payload.has("takServerList")) {
            currentServerList = parseTakServerList(payload);
            runOnMain(() -> applyServersToUi(currentServerList));
        }
        updateSettingsSendState();
    }

    private void savePreferredDevice(String address, String nameOrNull) {
        if (pluginContext == null) return;
        if (address == null || address.trim().isEmpty()) return;

        pluginContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PREF_ADDR, address.trim())
                .putString(KEY_PREF_NAME, nameOrNull == null ? "" : nameOrNull)
                .putLong(KEY_PREF_SET_AT, System.currentTimeMillis())
                .apply();
    }

    private void clearPreferredDevice(String address) {
        if (pluginContext == null) return;

        SharedPreferences prefs = pluginContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String current = prefs.getString(KEY_PREF_ADDR, null);
        if (address != null) {
            if (current == null) return;
            current = current.trim();
            if (!address.equals(current)) return;
        }

        prefs.edit()
                .remove(KEY_PREF_ADDR)
                .remove(KEY_PREF_NAME)
                .remove(KEY_PREF_SET_AT)
                .apply();
    }

    private String loadPreferredAddress() {
        if (pluginContext == null) return null;
        String v = pluginContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PREF_ADDR, null);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    private String loadPreferredName() {
        if (pluginContext == null) return null;
        String v = pluginContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PREF_NAME, null);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    @Override
    public String getShortDescription() {
        return pluginContext.getString(R.string.app_name);
    }

    @Override
    public Drawable getIcon() {
        return pluginContext.getResources().getDrawable(R.drawable.weartak_companion_plugin);
    }

    @Override
    public String getDescription() {
        return pluginContext.getString(R.string.app_desc);
    }

    @Override
    public void onItemEvent(MotionEvent motionEvent) {
        showPane();
    }

}
