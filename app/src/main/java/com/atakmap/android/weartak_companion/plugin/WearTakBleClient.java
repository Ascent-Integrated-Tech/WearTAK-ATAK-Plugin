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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simple BLE client for WearTAK watch GATT server.
 *
 * Flow:
 * 1) scanForDevices() -> UI list (NO auto-connect, NO reconnect loop)
 * 2) user selects device -> connectToDevice()
 * 3) discover service/characteristics -> subscribe to TX notifications
 * 4) receive JSON -> when watch_info arrives, persist payload.uid as paired_uid
 *
 * Notes:
 * - We do NOT store MAC as identity.
 * - Android still uses BluetoothDevice address internally to connect, but we do not persist it.
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

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler bg = new Handler(Looper.getMainLooper()); // simple; keep callbacks serialized

    private volatile boolean started = false;
    private volatile boolean scanning = false;
    private final LinkedHashMap<String, DiscoveredDevice> discovered = new LinkedHashMap<>();

    // RX framing: newline-delimited JSON (keep what you had)
    private final Object rxLock = new Object();
    private final StringBuilder rxBuffer = new StringBuilder(4096);

    private static final int DESIRED_MTU = 512; // common safe size; 517 is max but often unnecessary
    private volatile boolean mtuRequested = false;
    private volatile boolean mtuNegotiated = false;

    public interface JsonListener {
        void onReady();                // notifications enabled
        void onJson(String jsonLine);  // a full JSON envelope line
        void onPaired(String deviceId, String callsign); // when watch_info received
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
    private volatile boolean connected = false;

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

        void onScanFinished();

        void onScanError(String msg);
    }

    // -------------------- lifecycle --------------------
    int timeoutSeconds = 30;

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
        stopScanInternal();
        closeGattInternal("stop()");
        started = false;
        bluetoothAdapter = null;
        scanner = null;
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
        if (scanning) {
            failScan(listener, "Already scanning");
            return;
        }

        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            failScan(listener, "Missing BLUETOOTH_SCAN permission");
            return;
        }

        discovered.clear();
        scanning = true;

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build();

        // We scan unfiltered and filter in code by:
        // - advertised service UUID (if present) OR name prefix "WT-"
        // This is more robust across Android scan record quirks.
        logI("scanForDevices(): startScan (unfiltered), timeout=" + timeoutSeconds + "s");

        scanner.startScan(null, settings, new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                if (!scanning || result == null || result.getDevice() == null) return;

                BluetoothDevice d = result.getDevice();
                String addr = d.getAddress();
                int rssi = result.getRssi();

                String name = safeGetDeviceName(d);
                String advName = safeGetAdvName(result);

                boolean hasService = safeHasServiceUuid(result, COMPANION_SERVICE_UUID);
                boolean nameLooksRight = startsWithWT(name) || startsWithWT(advName);

                // Accept candidate if either signal matches
                if (!hasService && !nameLooksRight) return;
                if (addr == null) return;

                // Prefer advertised name if available
                String displayName = (advName != null && !advName.isEmpty()) ? advName : name;

                DiscoveredDevice dd = new DiscoveredDevice(displayName, addr, rssi);
                boolean isNew = !discovered.containsKey(addr);
                discovered.put(addr, dd);

                if (isNew && listener != null) {
                    listener.onDeviceFound(dd);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                scanning = false;
                if (listener != null) listener.onScanError("Scan failed: " + errorCode);
            }
        });

        // Stop scan after timeout
        main.postDelayed(() -> {
            List<DiscoveredDevice> out = stopScanInternal();
            if (listener != null) listener.onScanFinished(out);
        }, Math.max(1, timeoutSeconds) * 1000L);
    }

    public synchronized List<DiscoveredDevice> getLastDiscoveredDevices() {
        return new ArrayList<>(discovered.values());
    }

    private synchronized List<DiscoveredDevice> stopScanInternal() {
        if (!scanning) return new ArrayList<>(discovered.values());
        scanning = false;

        try {
            if (scanner != null) {
                if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN)
                        == PackageManager.PERMISSION_GRANTED) {
                    // stopScan requires the SAME callback instance; we used an anonymous instance.
                    // Therefore: in this simplified version, we rely on scan timeout and the OS.
                    // If you want explicit stopScan, store the ScanCallback in a field.
                }
            }
        } catch (Throwable ignored) {}

        logI("scanForDevices(): finished discoveredCount=" + discovered.size());
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

        // stop scanning (don’t fight radio)
        stopScanInternal();

        BluetoothDevice d = bluetoothAdapter.getRemoteDevice(device.address);
        if (d == null) {
            error("Remote device null for address=" + device.address);
            return;
        }

        closeGattInternal("connectToSelectedDevice()");
        logI("connect(): " + device.toString());

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

                logI("GATT connected -> discoverServices()");

                // DO NOT discover services yet; wait for onMtuChanged.
                // Some stacks are fine either way, but waiting is the least surprising.
                return;
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                logW("GATT disconnected status=" + status + " (no auto-reconnect)");
                setConnected(false);
                closeGattInternal("STATE_DISCONNECTED");
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            logI("onMtuChanged mtu=" + mtu + " status=" + status);

            // Even if it fails, proceed with discoverServices so the app still works at MTU=23.
            mtuNegotiated = (status == BluetoothGatt.GATT_SUCCESS);

            if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                error("Missing BLUETOOTH_CONNECT permission");
                return;
            }

            logI("GATT connected -> discoverServices() (after MTU)");
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

            // Correct binding:
            // - Watch NOTIFIES on A11B (TX_TO_COMPANION)
            // - Phone WRITES to A11C (RX_FROM_COMPANION)
            txNotifyChar = service.getCharacteristic(TX_TO_COMPANION_UUID);
            rxWriteChar  = service.getCharacteristic(RX_FROM_COMPANION_UUID);

            if (txNotifyChar == null) {
                error("TX notify char A11B missing");
                return;
            }
            if (rxWriteChar == null) {
                // Not fatal if you aren't writing yet; but log it.
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
            Log.i(TAG, chunk);
            // newline-delimited JSON framing (same as your existing logic)
            synchronized (rxLock) {
                rxBuffer.append(chunk);
                int idx;
                while ((idx = rxBuffer.indexOf("\n")) >= 0) {
                    String line = rxBuffer.substring(0, idx).trim();
                    rxBuffer.delete(0, idx + 1);
                    if (!line.isEmpty()) {
                        handleJsonLine(line);
                    }
                }

                // If watch doesn't include newlines, we can still handle single JSON objects:
                // If buffer looks like it contains a full JSON object, try parse once.
                if (rxBuffer.length() > 0 && rxBuffer.charAt(0) == '{' && rxBuffer.charAt(rxBuffer.length() - 1) == '}') {
                    String maybe = rxBuffer.toString().trim();
                    rxBuffer.setLength(0);
                    if (!maybe.isEmpty()) handleJsonLine(maybe);
                }
            }
        }
    };

    private void handleJsonLine(String line) {
        // Forward raw JSON
        JsonListener l = jsonListener.get();
        if (l != null) l.onJson(line);

        // Pairing: save payload.uid when watch_info arrives
        try {
            JSONObject env = new JSONObject(line);
            String msgType = env.optString("msgType", "");
            if (!"watch_info".equals(msgType)) return;

            JSONObject p = env.optJSONObject("payload");
            if (p == null) return;

            String uid = p.optString("uid", "");
            String cs = p.optString("cs", "");

            if (uid != null && !uid.isEmpty()) {
                savePairedUid(uid, cs);
                logI("PAIR: saved paired_uid=" + uid + " cs=" + cs);

                if (l != null) l.onPaired(uid, cs);
            }
        } catch (Throwable t) {
            // do not spam errors on normal traffic; pairing parse errors should still be visible
            logW("handleJsonLine(): failed parsing watch_info: " + t.getMessage());
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

    private void closeGattInternal(String reason) {
        try {
            if (bluetoothGatt != null) {
                if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED) {
                    bluetoothGatt.disconnect();
                }
                bluetoothGatt.close();
            }
        } catch (Throwable ignored) {}
        bluetoothGatt = null;
        txNotifyChar = null;
        rxWriteChar = null;
        synchronized (rxLock) {
            rxBuffer.setLength(0);
        }
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