package com.weartak.atak.weartak_companion.plugin;

import android.content.Context;
import android.util.Log;

import com.atakmap.android.maps.MapView;

import com.garmin.android.connectiq.ConnectIQ;
import com.garmin.android.connectiq.IQApp;
import com.garmin.android.connectiq.IQDevice;
import com.garmin.android.connectiq.exception.InvalidStateException;
import com.garmin.android.connectiq.exception.ServiceUnavailableException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class GarminConnectIqClient {

    private static final String TAG = "WTK/GarminCIQ";
    private static final String WATCH_APP_ID = "5721f67e-bcc4-47e8-b337-2ad96ee77c0a";
    private static final String WATCH_RELAY_INSTRUCTIONS =
            "On WearTAK-Garmin, select Settings, select Network Preferences, then toggle on ATAK Relay.";

    public interface Listener {
        void onStatusChanged(String status);
        void onConnectionChanged(boolean connected);
        void onMessageReceived(JSONObject envelope);
    }

    private static final class DeviceApp {
        final IQDevice device;
        IQApp app;
        boolean appEventsRegistered;
        boolean appInfoPending;
        volatile boolean connected;
        DeviceApp(IQDevice device) {
            this.device = device;
        }
    }

    private final Context appContext;
    private final Listener listener;
    private final Object lock = new Object();
    private final Map<Long, DeviceApp> devices = new HashMap<>();

    private ConnectIQ connectIQ;
    private boolean started;
    private boolean sdkReady;
    private boolean initializing;
    private String status = "Garmin Connect IQ is off.";

    public GarminConnectIqClient(Context context, Listener listener) {
        this.appContext = resolveHostContext(context);
        this.listener = listener;
        this.connectIQ = ConnectIQ.getInstance(appContext, ConnectIQ.IQConnectType.WIRELESS);
    }

    // ATAK plugin contexts return null from getApplicationContext(), so the Connect IQ SDK
    // must be given the host ATAK application context to register receivers and bind services.
    private static Context resolveHostContext(Context context) {
        MapView mapView = MapView.getMapView();
        if (mapView != null && mapView.getContext() != null) {
            Context hostApp = mapView.getContext().getApplicationContext();
            return hostApp != null ? hostApp : mapView.getContext();
        }
        Context app = context.getApplicationContext();
        return app != null ? app : context;
    }

    public void start() {
        boolean alreadyStarted;
        boolean refresh;
        synchronized (lock) {
            alreadyStarted = started;
            refresh = started && sdkReady;
            if (!started) {
                started = true;
            }
        }
        if (alreadyStarted) {
            if (refresh) refreshDevices();
            else initializeSdk();
            return;
        }

        publishStatus("Connecting to Garmin Connect Mobile.");
        initializeSdk();
    }

    private void initializeSdk() {
        synchronized (lock) {
            if (!started || sdkReady || initializing) return;
            initializing = true;
        }

        try {
            connectIQ.initialize(appContext, false, createSdkListener());
        } catch (RuntimeException e) {
            Log.e(TAG, "Connect IQ initialization failed", e);
            synchronized (lock) {
                initializing = false;
            }
            publishConnectionState();
            publishStatus("Garmin Connect IQ failed to start: " + e.getMessage());
        }
    }

    private ConnectIQ.ConnectIQListener createSdkListener() {
        return new ConnectIQ.ConnectIQListener() {
            @Override
            public void onSdkReady() {
                synchronized (lock) {
                    initializing = false;
                    if (!started) return;
                    sdkReady = true;
                }
                refreshDevices();
            }

            @Override
            public void onInitializeError(ConnectIQ.IQSdkErrorStatus errorStatus) {
                synchronized (lock) {
                    initializing = false;
                    if (!started) return;
                }
                publishConnectionState();
                publishStatus("Garmin Connect IQ unavailable: " + errorStatus);
            }

            @Override
            public void onSdkShutDown() {
                boolean shouldNotify;
                synchronized (lock) {
                    if (!started) return;
                    sdkReady = false;
                    initializing = false;
                    for (DeviceApp device : devices.values()) {
                        device.connected = false;
                    }
                    shouldNotify = started;
                }
                publishConnectionState();
                if (shouldNotify) publishStatus("Garmin Connect IQ stopped.");
            }
        };
    }

    public void stop() {
        synchronized (lock) {
            if (!started) return;
            started = false;
            sdkReady = false;
            initializing = false;
            devices.clear();
        }
        publishConnectionState();
        publishStatus("Garmin Connect IQ is off.");

        try {
            connectIQ.unregisterAllForEvents();
        } catch (InvalidStateException e) {
            Log.w(TAG, "Unable to unregister Connect IQ event listeners", e);
        }

        try {
            connectIQ.shutdown(appContext);
        } catch (InvalidStateException e) {
            Log.w(TAG, "Unable to shut down Connect IQ SDK", e);
        }
    }

    public void refreshDevices() {
        boolean ready;
        synchronized (lock) {
            if (!started) return;
            ready = sdkReady;
        }
        if (!ready) {
            publishStatus("Waiting for Garmin Connect Mobile; retrying SDK initialization.");
            initializeSdk();
            return;
        }

        final List<IQDevice> knownDevices;
        try {
            knownDevices = connectIQ.getKnownDevices();
        } catch (InvalidStateException | ServiceUnavailableException e) {
            Log.e(TAG, "Unable to list paired Garmin devices", e);
            publishStatus("Could not read Garmin devices: " + e.getMessage());
            return;
        }

        if (knownDevices == null || knownDevices.isEmpty()) {
            publishConnectionState();
            publishStatus("No Garmin watches paired in Garmin Connect Mobile.");
            return;
        }

        for (IQDevice device : knownDevices) {
            if (device != null) registerDevice(device);
        }
        publishConnectionState();
        publishStatus("Found " + knownDevices.size()
                + " Garmin device(s). " + WATCH_RELAY_INSTRUCTIONS);
    }

    public String getStatus() {
        synchronized (lock) {
            return status;
        }
    }

    public boolean isConnected() {
        synchronized (lock) {
            for (DeviceApp device : devices.values()) {
                if (device.connected) return true;
            }
            return false;
        }
    }

    public boolean sendMessage(String msgType, Map<String, Object> payload) {
        final List<DeviceApp> targets = new ArrayList<>();
        synchronized (lock) {
            if (!started || !sdkReady) return false;
            for (DeviceApp entry : devices.values()) {
                if (entry.app != null && entry.connected) {
                    targets.add(entry);
                }
            }
        }

        if (targets.isEmpty()) {
            Log.w(TAG, "No connected Garmin watch with WearTAK available for " + msgType);
            return false;
        }

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("msgType", msgType);
        envelope.put("payload", payload == null ? new HashMap<String, Object>() : payload);

        for (DeviceApp target : targets) {
            try {
                connectIQ.sendMessage(target.device, target.app, envelope,
                        (device, app, messageStatus) -> {
                            synchronized (lock) {
                                if (!started || devices.get(target.device.getDeviceIdentifier()) != target) return;
                            }
                            if (messageStatus == ConnectIQ.IQMessageStatus.SUCCESS) {
                                Log.i(TAG, "Sent " + msgType + " to " + device.getFriendlyName());
                            } else {
                                Log.w(TAG, "Message " + msgType + " to "
                                        + device.getFriendlyName() + " failed: " + messageStatus);
                                publishStatus("Garmin message failed: " + messageStatus);
                            }
                        });
            } catch (InvalidStateException | ServiceUnavailableException | IllegalArgumentException e) {
                Log.e(TAG, "Unable to send " + msgType + " to "
                        + target.device.getFriendlyName(), e);
                publishStatus("Unable to send Garmin message: " + e.getMessage());
            }
        }
        return true;
    }

    private void registerDevice(IQDevice device) {
        final DeviceApp entry;
        boolean isNewDevice;
        synchronized (lock) {
            if (!started || !sdkReady) return;
            long deviceId = device.getDeviceIdentifier();
            DeviceApp existing = devices.get(deviceId);
            if (existing == null) {
                entry = new DeviceApp(device);
                devices.put(deviceId, entry);
                isNewDevice = true;
            } else {
                entry = existing;
                isNewDevice = false;
            }
        }

        if (!isNewDevice) {
            loadApplication(entry);
            return;
        }

        try {
            connectIQ.registerForDeviceEvents(device, (changedDevice, newStatus) -> {
                synchronized (lock) {
                    if (!started || devices.get(device.getDeviceIdentifier()) != entry) return;
                }
                entry.connected = newStatus == IQDevice.IQDeviceStatus.CONNECTED;
                publishConnectionState();
                if (entry.connected) {
                    loadApplication(entry);
                    publishStatus("Garmin connected: " + changedDevice.getFriendlyName()
                            + ". " + WATCH_RELAY_INSTRUCTIONS);
                } else {
                    publishStatus("Garmin not connected: " + changedDevice.getFriendlyName());
                }
            });
            try {
                entry.connected = connectIQ.getDeviceStatus(device)
                        == IQDevice.IQDeviceStatus.CONNECTED;
            } catch (InvalidStateException | ServiceUnavailableException e) {
                Log.w(TAG, "Unable to read Garmin device status", e);
            }
            publishConnectionState();
            loadApplication(entry);
        } catch (InvalidStateException e) {
            synchronized (lock) {
                devices.remove(device.getDeviceIdentifier());
            }
            Log.e(TAG, "Unable to register Garmin device events", e);
            publishStatus("Could not monitor Garmin device: " + e.getMessage());
        }
    }

    private void loadApplication(DeviceApp entry) {
        synchronized (lock) {
            if (!started || !sdkReady || entry.app != null || entry.appInfoPending) return;
            entry.appInfoPending = true;
        }

        try {
            connectIQ.getApplicationInfo(WATCH_APP_ID, entry.device,
                    new ConnectIQ.IQApplicationInfoListener() {
                        @Override
                        public void onApplicationInfoReceived(IQApp app) {
                            synchronized (lock) {
                                entry.appInfoPending = false;
                                if (!started || app == null) return;
                                entry.app = app;
                            }
                            registerAppEvents(entry);
                        }

                        @Override
                        public void onApplicationNotInstalled(String applicationId) {
                            synchronized (lock) {
                                if (!started) return;
                                entry.appInfoPending = false;
                            }
                            publishStatus("WearTAK is not installed on "
                                    + entry.device.getFriendlyName() + ".");
                        }
                    });
        } catch (InvalidStateException | ServiceUnavailableException e) {
            synchronized (lock) {
                entry.appInfoPending = false;
            }
            Log.e(TAG, "Unable to check for WearTAK on Garmin watch", e);
            publishStatus("Could not check WearTAK on Garmin: " + e.getMessage());
        }
    }

    private void registerAppEvents(DeviceApp entry) {
        synchronized (lock) {
            if (!started || entry.app == null || entry.appEventsRegistered) return;
            entry.appEventsRegistered = true;
        }

        try {
            connectIQ.registerForAppEvents(entry.device, entry.app,
                    (device, app, messageData, messageStatus) -> {
                        synchronized (lock) {
                            if (!started) return;
                        }
                        if (messageStatus != ConnectIQ.IQMessageStatus.SUCCESS) {
                            Log.w(TAG, "Garmin message receive failed: " + messageStatus);
                            publishStatus("Garmin receive error: " + messageStatus);
                            return;
                        }
                        if (messageData == null) return;
                        // A message from the watch proves the link is up even if no
                        // device status event has been delivered yet.
                        entry.connected = true;
                        publishConnectionState();
                        for (Object item : messageData) {
                            if (item instanceof Map) {
                                JSONObject envelope = toJsonObject((Map<?, ?>) item);
                                if (envelope != null && listener != null) {
                                    listener.onMessageReceived(envelope);
                                }
                            }
                        }
                    });
            publishStatus("WearTAK found on " + entry.device.getFriendlyName()
                    + ". " + WATCH_RELAY_INSTRUCTIONS);
        } catch (InvalidStateException | ServiceUnavailableException e) {
            synchronized (lock) {
                entry.appEventsRegistered = false;
            }
            Log.e(TAG, "Unable to register Garmin app events", e);
            publishStatus("Could not listen for Garmin messages: " + e.getMessage());
        }
    }

    private JSONObject toJsonObject(Map<?, ?> values) {
        JSONObject result = new JSONObject();
        for (Map.Entry<?, ?> value : values.entrySet()) {
            if (!(value.getKey() instanceof String)) continue;
            try {
                result.put((String) value.getKey(), toJsonValue(value.getValue()));
            } catch (JSONException e) {
                Log.w(TAG, "Skipping unsupported Garmin message value", e);
            }
        }
        return result;
    }

    private Object toJsonValue(Object value) {
        if (value instanceof Map) return toJsonObject((Map<?, ?>) value);
        if (value instanceof List) {
            JSONArray array = new JSONArray();
            for (Object item : (List<?>) value) array.put(toJsonValue(item));
            return array;
        }
        return value;
    }

    private void publishStatus(String newStatus) {
        synchronized (lock) {
            if (!started && !"Garmin Connect IQ is off.".equals(newStatus)) return;
            status = newStatus;
        }
        if (listener != null) listener.onStatusChanged(newStatus);
    }

    private void publishConnectionState() {
        boolean connected = false;
        synchronized (lock) {
            for (DeviceApp device : devices.values()) {
                if (device.connected) {
                    connected = true;
                    break;
                }
            }
        }
        if (listener != null) listener.onConnectionChanged(connected);
    }
}
