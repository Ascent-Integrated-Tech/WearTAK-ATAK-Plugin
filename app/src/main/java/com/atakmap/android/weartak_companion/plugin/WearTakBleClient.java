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
import android.util.Log;

import androidx.core.app.ActivityCompat;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * BLE client for WearTAK watch GATT server.
 * Adds chunked writes so large JSON payloads can be reliably sent to the watch.
 */
public class WearTakBleClient {

    private static final String TAG = "WTK/BleClient";

    public static final UUID COMPANION_SERVICE_UUID =
            UUID.fromString("0000A11A-0000-1000-8000-00805F9B34FB");
    public static final UUID TX_TO_COMPANION_UUID =
            UUID.fromString("0000A11B-0000-1000-8000-00805F9B34FB"); // NOTIFY watch -> phone
    public static final UUID RX_FROM_COMPANION_UUID =
            UUID.fromString("0000A11C-0000-1000-8000-00805F9B34FB"); // WRITE phone -> watch
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
    private static final String KEY_PREFERRED_ADDRESS = "preferred_address"; // saved when connected

    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean started = false;
    private volatile boolean scanning = false;
    private volatile boolean connected = false;

    private final LinkedHashMap<String, DiscoveredDevice> discovered = new LinkedHashMap<>();

    private ScanCallback activeScanCallback = null;
    private Runnable scanTimeoutRunnable = null;

    // RX framing: newline-delimited JSON
    private final Object rxLock = new Object();
    private final StringBuilder rxBuffer = new StringBuilder(4096);

    private static final int DESIRED_MTU = 512;
    private volatile boolean mtuNegotiated = false;
    private volatile int negotiatedMtu = 23; // default ATT MTU if unknown

    private static final long RECONNECT_COOLDOWN_MS = 250;
    private long lastGattCloseMs = 0L;

    // -------- TX queue (chunked writes) --------
    private final Object txLock = new Object();
    private final Deque<byte[]> txQueue = new ArrayDeque<>();
    private volatile boolean txInFlight = false;

    /**
     * [Inference] Conservative chunk size to avoid edge MTU/stack issues.
     * If MTU is negotiated, we compute (mtu - 3) and also clamp to 180.
     */
    private int computeMaxChunk() {
        int mtuPayload = Math.max(20, negotiatedMtu - 3);
        return Math.min(180, mtuPayload);
    }

    public interface JsonListener {
        void onReady();
        void onJson(String jsonLine);
        void onPaired(String deviceId, String callsign);
        void onError(String msg);
    }

    private final AtomicReference<JsonListener> jsonListener = new AtomicReference<>(null);
    public void setJsonListener(JsonListener l) { jsonListener.set(l); }

    public interface StatusListener {
        void onConnectionStatusChanged(boolean connected);
    }

    private final StatusListener statusListener;

    public WearTakBleClient(Context context, StatusListener statusListener) {
        this.baseContext = context;
        this.statusListener = statusListener;
    }

    private void savePreferredAddress(String addr) {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_PREFERRED_ADDRESS, addr).apply();
    }

    public String getPreferredAddress() {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getString(KEY_PREFERRED_ADDRESS, null);
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
        if (started) return;
        if (baseContext == null) return;

        appContext = baseContext.getApplicationContext();
        if (appContext == null) appContext = baseContext;

        BluetoothManager bm = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) return;

        bluetoothAdapter = bm.getAdapter();
        if (bluetoothAdapter == null) return;

        scanner = bluetoothAdapter.getBluetoothLeScanner();
        started = true;
        logI("start(): initialized (no auto-connect)");
    }

    public synchronized void stop() {
        stopScanInternal("stop()");
        closeGattInternal("stop()");
        started = false;
        bluetoothAdapter = null;
        scanner = null;
        setConnected(false);
    }

    public synchronized void resetSession(String reason) {
        logI("resetSession(): " + reason);
        stopScanInternal("resetSession: " + reason);
        closeGattInternal("resetSession: " + reason);

        synchronized (rxLock) { rxBuffer.setLength(0); }
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
        if (scanner == null) scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            failScan(listener, "BLE scanner null");
            return;
        }

        stopScanInternal("scanForDevices(): pre-stop");

        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            failScan(listener, "Missing BLUETOOTH_SCAN permission");
            return;
        }

        discovered.clear();
        scanning = true;

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
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

                if (!hasService) return;

                String displayName = (advName != null && !advName.isEmpty()) ? advName : name;
                String strippedName = displayName.split("-")[1];
                DiscoveredDevice dd = new DiscoveredDevice(strippedName, addr, rssi);

                discovered.put(addr, dd);

                if (listener != null) listener.onDeviceFound(dd);
                Log.d(TAG, "SCAN: addr=" + addr + " name=" + name + " advName=" + advName
                        + " displayName=" + displayName + " hasService=" + hasService + " rssi=" + rssi);
            }

            @Override
            public void onScanFailed(int errorCode) {
                scanning = false;
                clearScanTimeout();
                activeScanCallback = null;
                if (listener != null) listener.onScanError("Scan failed: " + errorCode);
            }
        };

        try {
            scanner.startScan(null, settings, activeScanCallback);
        } catch (Throwable t) {
            scanning = false;
            activeScanCallback = null;
            clearScanTimeout();
            failScan(listener, "startScan threw: " + t.getMessage());
            return;
        }

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
        if (device == null || device.address == null) return;
        if (!started) start();

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            error("Bluetooth disabled");
            return;
        }

        stopScanInternal("connectToSelectedDevice()");

        long now = android.os.SystemClock.uptimeMillis();
        long dt = now - lastGattCloseMs;
        long waitMs = (dt < RECONNECT_COOLDOWN_MS) ? (RECONNECT_COOLDOWN_MS - dt) : 0;

        if (waitMs > 0) {
            final DiscoveredDevice copy = device;
            main.postDelayed(() -> connectGattNow(copy), waitMs);
        } else {
            connectGattNow(device);
        }
    }

    private synchronized void connectGattNow(DiscoveredDevice device) {
        if (device == null || device.address == null) return;

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            error("Bluetooth disabled");
            return;
        }

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

        bluetoothGatt = d.connectGatt(appContext, false, gattCallback);
    }

    public synchronized void disconnect() {
        closeGattInternal("disconnect()");
        setConnected(false);
    }

    // -------------------- NEW: outbound JSON write (chunked) --------------------

    public synchronized boolean writeJsonLineToWatch(String jsonLine) {
        if (jsonLine == null) return false;
        if (!started) start();

        if (bluetoothGatt == null || !connected) {
            error("writeJsonLineToWatch: not connected");
            return false;
        }
        if (rxWriteChar == null) {
            error("writeJsonLineToWatch: RX write characteristic (A11C) missing");
            return false;
        }

        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            error("Missing BLUETOOTH_CONNECT permission");
            return false;
        }

        String payload = jsonLine.endsWith("\n") ? jsonLine : (jsonLine + "\n");
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

        int maxChunk = computeMaxChunk();
        synchronized (txLock) {
            // split into chunks
            int off = 0;
            while (off < bytes.length) {
                int n = Math.min(maxChunk, bytes.length - off);
                byte[] chunk = new byte[n];
                System.arraycopy(bytes, off, chunk, 0, n);
                txQueue.addLast(chunk);
                off += n;
            }

            // kick the queue if idle
            if (!txInFlight) {
                txInFlight = true;
                main.post(this::txSendNextLocked);
            }
        }

        logI("TX->WATCH queued bytes=" + bytes.length + " chunks=" + ((bytes.length + maxChunk - 1) / maxChunk));
        return true;
    }

    private void txSendNextLocked() {
        byte[] next;
        synchronized (txLock) {
            next = txQueue.pollFirst();
            if (next == null) {
                txInFlight = false;
                return;
            }
        }

        if (bluetoothGatt == null || rxWriteChar == null) {
            synchronized (txLock) {
                txQueue.clear();
                txInFlight = false;
            }
            error("TX aborted: gatt/char null");
            return;
        }

        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            synchronized (txLock) {
                txQueue.clear();
                txInFlight = false;
            }
            error("TX aborted: missing BLUETOOTH_CONNECT permission");
            return;
        }

        rxWriteChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        rxWriteChar.setValue(next);

        boolean ok = bluetoothGatt.writeCharacteristic(rxWriteChar);
        if (!ok) {
            synchronized (txLock) {
                txQueue.clear();
                txInFlight = false;
            }
            error("TX writeCharacteristic returned false");
        }
    }

    public synchronized boolean isConnected() { return connected; }

    // -------------------- GATT --------------------

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setConnected(true);

                if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    error("Missing BLUETOOTH_CONNECT permission");
                    return;
                }

                gatt.requestMtu(DESIRED_MTU);
                return;
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                setConnected(false);
                closeGattInternal("STATE_DISCONNECTED status=" + status);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            mtuNegotiated = (status == BluetoothGatt.GATT_SUCCESS);
            if (mtuNegotiated) negotiatedMtu = mtu;

            if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                error("Missing BLUETOOTH_CONNECT permission");
                return;
            }

            gatt.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
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
            if (!wrote) error("writeDescriptor(CCCD) failed");
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (descriptor == null) return;
            if (!CCCD_UUID.equals(descriptor.getUuid())) return;

            if (status == BluetoothGatt.GATT_SUCCESS) {
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
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (characteristic == null) return;
            if (rxWriteChar == null) return;
            if (!characteristic.getUuid().equals(rxWriteChar.getUuid())) return;

            if (status != BluetoothGatt.GATT_SUCCESS) {
                synchronized (txLock) {
                    txQueue.clear();
                    txInFlight = false;
                }
                error("TX chunk write failed status=" + status);
                return;
            }

            // send next chunk
            main.post(WearTakBleClient.this::txSendNextLocked);
        }
    };

    private void handleJsonLine(String line) {
        JsonListener l = jsonListener.get();
        if (l != null) l.onJson(line);

        // existing pairing support
        try {
            JSONObject env = new JSONObject(line);
            String msgType = env.optString("msgType", "");
            if (!"watch_info".equals(msgType)) return;

            JSONObject p = env.optJSONObject("payload");
            if (p == null) return;

            String uid = p.optString("uid", "");
            String cs  = p.optString("cs", "");

            if (uid != null && !uid.isEmpty()) {
                savePairedUid(uid, cs);
                if (l != null) l.onPaired(uid, cs);
            }
        } catch (Throwable ignored) { }
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

        synchronized (rxLock) { rxBuffer.setLength(0); }
        synchronized (txLock) { txQueue.clear(); txInFlight = false; }

        lastGattCloseMs = android.os.SystemClock.uptimeMillis();
        logI("closeGatt(): " + reason);
    }

    private void setConnected(boolean value) {
        if (connected != value) {
            connected = value;
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

    private boolean startsWithWT(String s) { return s != null && s.startsWith("WT-"); }

    private void logI(String msg) { android.util.Log.i(TAG, msg); }
    private void logW(String msg) { android.util.Log.w(TAG, msg); }
}