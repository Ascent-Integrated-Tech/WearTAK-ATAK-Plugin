package com.atakmap.android.weartak_companion.plugin;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import androidx.core.app.ActivityCompat;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * BLE client for WearTAK watch GATT server.
 *
 * Reset behavior:
 * - resetSession(): stop scan, close gatt, clear buffers/state.
 * - Scan button should call resetSession() before scanning.
 * - Clicking a device should call resetSession() before connect.
 */
public class WearTakBleClient {

    private static final String TAG = "WTK/BleClient";

    // WearTAK GATT (watch exposes server)
    public static final UUID COMPANION_SERVICE_UUID =
            UUID.fromString("0000A11A-0000-1000-8000-00805F9B34FB");
    public static final UUID TX_TO_COMPANION_UUID =
            UUID.fromString("0000A11B-0000-1000-8000-00805F9B34FB"); // NOTIFY from watch -> phone
    public static final UUID RX_FROM_COMPANION_UUID =
            UUID.fromString("0000A11C-0000-1000-8000-00805F9B34FB"); // WRITE from phone -> watch
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final String PREFS = "wtk_pairing";
    private static final String KEY_PAIRED_UID = "paired_uid";
    private static final String KEY_PAIRED_CALLSIGN = "paired_callsign";

    private final Context baseContext;
    private Context appContext;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner scanner;

    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic txNotifyChar; // A11B
    private BluetoothGattCharacteristic rxWriteChar;  // A11C

    // Handlers
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler bg = new Handler(Looper.getMainLooper()); // keep callbacks serialized

    // State
    private volatile boolean started = false;
    private volatile boolean scanning = false;
    private volatile boolean connected = false;

    private final LinkedHashMap<String, DiscoveredDevice> discovered = new LinkedHashMap<>();

    // Active scan callback + timeout runnable (so we can stop scan reliably)
    private ScanCallback activeScanCallback = null;
    private Runnable scanTimeoutRunnable = null;

    // RX framing: newline-delimited JSON
    private final Object rxLock = new Object();
    private final StringBuilder rxBuffer = new StringBuilder(4096);

    private static final int DESIRED_MTU = 512;
    private volatile boolean mtuRequested = false;
    private volatile boolean mtuNegotiated = false;

    // Optional: prevent immediate reconnect after close (reduces GATT 133 flakiness)
    private static final long RECONNECT_COOLDOWN_MS = 250;
    private long lastGattCloseMs = 0L;

    public interface JsonListener {
        void onReady();                // notifications enabled
        void onJson(String jsonLine);  // a full JSON envelope line
        void onPaired(String deviceId, String callsign);
        void onError(String msg);
    }

    private final AtomicReference<JsonListener> jsonListener = new AtomicReference<>(null);

    public void setJsonListener(JsonListener l) {
        jsonListener.set(l);
    }

    public interface StatusListener {
        void onConnectionStatusChanged(boolean connected);
    }

    private final StatusListener statusListener;

    public WearTakBleClient(Context context, StatusListener statusListener) {
        this.baseContext = context;
        this.statusListener = statusListener;
    }

    public static class DiscoveredDevice {
        public final String name;
        public final String address;
        public final int rssi;

        public DiscoveredDevice(String name, String address, int rssi) {
            this.name = name;
            this.address = address;
            this.rssi = rssi;
        }

        @Override public String toString() {
            String n = (name != null && !name.isEmpty()) ? name : "Unknown";
            return n + " (" + address + ")";
        }
    }

    public interface ScanListener {
        void onDeviceFound(DiscoveredDevice device);
        void onScanFinished(List<DiscoveredDevice> devices);
        void onScanError(String msg);
    }

    public int timeoutSeconds = 30;

    // -------------------- lifecycle --------------------

    public synchronized void start() {
        if (started) {
            logI("start(): already started");
            return;
        }
        if (baseContext == null) {
            logW("start(): null context");
            return;
        }
        appContext = baseContext.getApplicationContext();
        if (appContext == null) appContext = baseContext;

        BluetoothManager bm = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) {
            logW("start(): BluetoothManager null");
            return;
        }
        bluetoothAdapter = bm.getAdapter();
        if (bluetoothAdapter == null) {
            logW("start(): BluetoothAdapter null");
            return;
        }
        scanner = bluetoothAdapter.getBluetoothLeScanner();

        started = true;
        logI("start(): initialized (no auto-connect)");
    }

    public synchronized void stop() {
        logI("stop()");
        stopScanInternal("stop()");
        closeGattInternal("stop()");
        started = false;
        bluetoothAdapter = null;
        scanner = null;
        setConnected(false);
    }

    /**
     * Hard reset of the BLE session:
     * - stops scan
     * - closes gatt
     * - clears rx buffer / MTU flags
     * - clears "discovered" list (optional)
     */
    public synchronized void resetSession(String reason) {
        logI("resetSession(): " + reason);
        stopScanInternal("resetSession: " + reason);
        closeGattInternal("resetSession: " + reason);

        mtuRequested = false;
        mtuNegotiated = false;

        synchronized (rxLock) {
            rxBuffer.setLength(0);
        }

        discovered.clear();
        setConnected(false);
    }

    // -------------------- scanning --------------------

    public synchronized void scanForDevices(final ScanListener listener) {
        if (!started) start();

        if (bluetoothAdapter == null) {
            failScan(listener, "Bluetooth adapter null");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            failScan(listener, "Bluetooth disabled");
            return;
        }
        if (scanner == null) {
            scanner = bluetoothAdapter.getBluetoothLeScanner();
        }
        if (scanner == null) {
            failScan(listener, "BLE scanner null");
            return;
        }

        // Stop any previous scan cleanly
        stopScanInternal("scanForDevices(): pre-stop");

        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            failScan(listener, "Missing BLUETOOTH_SCAN permission");
            return;
        }

        discovered.clear();
        scanning = true;

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // better UX for "Scan"
                .build();

        activeScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                if (!scanning || result == null || result.getDevice() == null) return;

                BluetoothDevice d = result.getDevice();
                String addr = d.getAddress();
                if (addr == null) return;

                int rssi = result.getRssi();

                String name = safeGetDeviceName(d);
                String advName = safeGetAdvName(result);

                boolean hasService = safeHasServiceUuid(result, COMPANION_SERVICE_UUID);
                boolean nameLooksRight = startsWithWT(name) || startsWithWT(advName);

                if (!hasService && !nameLooksRight) return;

                String displayName = (advName != null && !advName.isEmpty()) ? advName : name;
                DiscoveredDevice dd = new DiscoveredDevice(displayName, addr, rssi);

                boolean isNew = !discovered.containsKey(addr);
                discovered.put(addr, dd);

                // Always notify; RSSI updates matter
                if (listener != null) listener.onDeviceFound(dd);
            }

            @Override
            public void onScanFailed(int errorCode) {
                scanning = false;
                clearScanTimeout();
                activeScanCallback = null;
                if (listener != null) listener.onScanError("Scan failed: " + errorCode);
            }
        };

        logI("scanForDevices(): startScan timeout=" + timeoutSeconds + "s");
        try {
            scanner.startScan(null, settings, activeScanCallback);
        } catch (Throwable t) {
            scanning = false;
            activeScanCallback = null;
            clearScanTimeout();
            failScan(listener, "startScan threw: " + t.getMessage());
            return;
        }

        // Schedule scan stop
        clearScanTimeout();
        scanTimeoutRunnable = () -> {
            List<DiscoveredDevice> out = stopScanInternal("scan timeout");
            if (listener != null) listener.onScanFinished(out);
        };
        main.postDelayed(scanTimeoutRunnable, Math.max(1, timeoutSeconds) * 1000L);
    }

    private synchronized void clearScanTimeout() {
        if (scanTimeoutRunnable != null) {
            main.removeCallbacks(scanTimeoutRunnable);
            scanTimeoutRunnable = null;
        }
    }

    private synchronized List<DiscoveredDevice> stopScanInternal(String reason) {
        if (!scanning) return new ArrayList<>(discovered.values());
        scanning = false;

        clearScanTimeout();

        try {
            if (scanner != null && activeScanCallback != null) {
                if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN)
                        == PackageManager.PERMISSION_GRANTED) {
                    scanner.stopScan(activeScanCallback);
                }
            }
        } catch (Throwable t) {
            logW("stopScanInternal(): stopScan threw: " + t.getMessage());
        } finally {
            activeScanCallback = null;
        }

        logI("stopScanInternal(): " + reason + " discoveredCount=" + discovered.size());
        return new ArrayList<>(discovered.values());
    }

    private void failScan(ScanListener listener, String msg) {
        logW("scanForDevices(): " + msg);
        if (listener != null) listener.onScanError(msg);
    }

    // -------------------- selection / connect --------------------

    public synchronized void connectToSelectedDevice(DiscoveredDevice device) {
        if (device == null || device.address == null) {
            logW("connectToSelectedDevice(): null device/address");
            return;
        }
        if (!started) start();

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            error("Bluetooth disabled");
            return;
        }

        // Stop scanning
        stopScanInternal("connectToSelectedDevice()");

        // IMPORTANT:
        // Do NOT closeGattInternal() here. The caller (UI) should have already called resetSession()
        // or disconnect(). Closing here + cooldown creates a self-perpetuating loop.

        // Cooldown: wait AFTER the most recent close, but do not recurse into connectToSelectedDevice()
        long now = android.os.SystemClock.uptimeMillis();
        long dt = now - lastGattCloseMs;
        long waitMs = (dt < RECONNECT_COOLDOWN_MS) ? (RECONNECT_COOLDOWN_MS - dt) : 0;

        if (waitMs > 0) {
            logI("connect(): cooldown " + waitMs + "ms before connectGatt");
            final DiscoveredDevice copy = device;
            main.postDelayed(() -> connectGattNow(copy), waitMs);
        } else {
            connectGattNow(device);
        }
    }

    private synchronized void connectGattNow(DiscoveredDevice device) {
        if (device == null || device.address == null) {
            logW("connectGattNow(): null device/address");
            return;
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            error("Bluetooth disabled");
            return;
        }

        // If something already connected/connecting, hard close once here (safe)
        // (This is not a loop because connectGattNow is never re-called from itself.)
        if (bluetoothGatt != null) {
            closeGattInternal("connectGattNow(): closing existing gatt");
            setConnected(false);
        }

        BluetoothDevice d = bluetoothAdapter.getRemoteDevice(device.address);
        if (d == null) {
            error("Remote device null for address=" + device.address);
            return;
        }

        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            error("Missing BLUETOOTH_CONNECT permission");
            return;
        }

        logI("connectGattNow(): connectGatt -> " + device.toString());
        bluetoothGatt = d.connectGatt(appContext, false, gattCallback);
    }

    public synchronized void disconnect() {
        closeGattInternal("disconnect()");
        setConnected(false);
    }

    // -------------------- GATT --------------------

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            logI("onConnectionStateChange status=" + status + " newState=" + newState);

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setConnected(true);

                if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    error("Missing BLUETOOTH_CONNECT permission");
                    return;
                }

                mtuRequested = true;
                mtuNegotiated = false;

                boolean mtuOk = gatt.requestMtu(DESIRED_MTU);
                logI("requestMtu(" + DESIRED_MTU + ") -> " + mtuOk);

                // Wait for onMtuChanged then discoverServices
                return;
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                logW("GATT disconnected status=" + status + " (no auto-reconnect)");
                setConnected(false);
                closeGattInternal("STATE_DISCONNECTED status=" + status);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            logI("onMtuChanged mtu=" + mtu + " status=" + status);

            mtuNegotiated = (status == BluetoothGatt.GATT_SUCCESS);

            if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                error("Missing BLUETOOTH_CONNECT permission");
                return;
            }

            logI("discoverServices() after MTU");
            gatt.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            logI("onServicesDiscovered status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                error("Service discovery failed: " + status);
                return;
            }

            BluetoothGattService service = gatt.getService(COMPANION_SERVICE_UUID);
            if (service == null) {
                error("Companion service A11A missing");
                return;
            }

            txNotifyChar = service.getCharacteristic(TX_TO_COMPANION_UUID);
            rxWriteChar  = service.getCharacteristic(RX_FROM_COMPANION_UUID);

            if (txNotifyChar == null) {
                error("TX notify char A11B missing");
                return;
            }
            if (rxWriteChar == null) {
                logW("RX write char A11C missing (write-to-watch disabled)");
            }

            enableTxNotifications(gatt, txNotifyChar);
        }

        private void enableTxNotifications(BluetoothGatt gatt, BluetoothGattCharacteristic tx) {
            if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                error("Missing BLUETOOTH_CONNECT permission");
                return;
            }

            boolean ok = gatt.setCharacteristicNotification(tx, true);
            if (!ok) {
                error("setCharacteristicNotification failed");
                return;
            }

            BluetoothGattDescriptor cccd = tx.getDescriptor(CCCD_UUID);
            if (cccd == null) {
                error("CCCD missing on A11B");
                return;
            }

            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);

            boolean wrote = gatt.writeDescriptor(cccd);
            if (!wrote) {
                error("writeDescriptor(CCCD) failed");
            } else {
                logI("CCCD write initiated (enable notifications)");
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (descriptor == null) return;
            if (!CCCD_UUID.equals(descriptor.getUuid())) return;

            if (status == BluetoothGatt.GATT_SUCCESS) {
                logI("Notifications enabled -> READY");
                JsonListener l = jsonListener.get();
                if (l != null) l.onReady();
            } else {
                error("CCCD write failed: " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (characteristic == null) return;
            if (txNotifyChar == null) return;
            if (!characteristic.getUuid().equals(txNotifyChar.getUuid())) return;

            byte[] data = characteristic.getValue();
            if (data == null || data.length == 0) return;

            String chunk = new String(data, StandardCharsets.UTF_8);

            synchronized (rxLock) {
                rxBuffer.append(chunk);
                int idx;
                while ((idx = rxBuffer.indexOf("\n")) >= 0) {
                    String line = rxBuffer.substring(0, idx).trim();
                    rxBuffer.delete(0, idx + 1);
                    if (!line.isEmpty()) handleJsonLine(line);
                }

                // If watch sometimes sends full JSON without newline:
                if (rxBuffer.length() > 1
                        && rxBuffer.charAt(0) == '{'
                        && rxBuffer.charAt(rxBuffer.length() - 1) == '}') {
                    String maybe = rxBuffer.toString().trim();
                    rxBuffer.setLength(0);
                    if (!maybe.isEmpty()) handleJsonLine(maybe);
                }
            }
        }
    };

    private void handleJsonLine(String line) {
        JsonListener l = jsonListener.get();
        if (l != null) l.onJson(line);

        // Pairing: persist payload.uid when watch_info arrives
        try {
            JSONObject env = new JSONObject(line);

            // Your plugin code uses "msgType" on receive; keep that convention here.
            String msgType = env.optString("msgType", "");
            if (!"watch_info".equals(msgType)) return;

            JSONObject p = env.optJSONObject("payload");
            if (p == null) return;

            String uid = p.optString("uid", "");
            String cs  = p.optString("cs", "");

            if (uid != null && !uid.isEmpty()) {
                savePairedUid(uid, cs);
                logI("PAIR: saved paired_uid=" + uid + " cs=" + cs);
                if (l != null) l.onPaired(uid, cs);
            }
        } catch (Throwable t) {
            logW("handleJsonLine(): watch_info parse failed: " + t.getMessage());
        }
    }

    // -------------------- pairing storage --------------------

    public String getPairedUid() {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getString(KEY_PAIRED_UID, null);
    }

    private void savePairedUid(String uid, String callsign) {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_PAIRED_UID, uid)
                .putString(KEY_PAIRED_CALLSIGN, callsign)
                .apply();
    }

    // -------------------- helpers --------------------

    private synchronized void closeGattInternal(String reason) {
        try {
            if (bluetoothGatt != null) {
                if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED) {
                    try { bluetoothGatt.disconnect(); } catch (Throwable ignored) {}
                }
                try { bluetoothGatt.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        bluetoothGatt = null;
        txNotifyChar = null;
        rxWriteChar = null;

        synchronized (rxLock) {
            rxBuffer.setLength(0);
        }

        lastGattCloseMs = android.os.SystemClock.uptimeMillis();
        logI("closeGatt(): " + reason);
    }

    private void setConnected(boolean value) {
        if (connected != value) {
            connected = value;
            logI("connected=" + connected);
            if (statusListener != null) statusListener.onConnectionStatusChanged(connected);
        }
    }

    private void error(String msg) {
        logW("ERROR: " + msg);
        JsonListener l = jsonListener.get();
        if (l != null) l.onError(msg);
    }

    private boolean safeHasServiceUuid(ScanResult r, UUID uuid) {
        try {
            if (r.getScanRecord() == null) return false;
            List<ParcelUuid> uuids = r.getScanRecord().getServiceUuids();
            if (uuids == null) return false;
            for (ParcelUuid pu : uuids) {
                if (pu != null && uuid.equals(pu.getUuid())) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private String safeGetAdvName(ScanResult r) {
        try {
            if (r.getScanRecord() == null) return null;
            return r.getScanRecord().getDeviceName();
        } catch (Throwable ignored) {}
        return null;
    }

    private String safeGetDeviceName(BluetoothDevice d) {
        try {
            if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) return null;
            return d.getName();
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean startsWithWT(String s) {
        return s != null && s.startsWith("WT-");
    }

    private void logI(String msg) {
        android.util.Log.i(TAG, msg);
    }

    private void logW(String msg) {
        android.util.Log.w(TAG, msg);
    }
}