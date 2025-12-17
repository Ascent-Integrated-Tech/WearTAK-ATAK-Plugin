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
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * V1 BLE client:
 *  - Connects to WearTAK GATT server exposing WearTAK Event Service
 *  - First time: scan by service UUID, connect, save MAC
 *  - Subsequent: reconnect by MAC
 *  - Reports connected/disconnected via StatusListener
 */
public class WearTakBleClient {

    private static final String TAG = "WTK/BleClient";

    // TODO: lock these UUIDs with the WearTAK team
// WearTAK GATT
    public static final UUID COMPANION_SERVICE_UUID =
            UUID.fromString("0000A11A-0000-1000-8000-00805F9B34FB");
    public static final UUID TX_TO_COMPANION_UUID =
            UUID.fromString("0000A11B-0000-1000-8000-00805F9B34FB");
    public static final UUID RX_FROM_COMPANION_UUID =
            UUID.fromString("0000A11C-0000-1000-8000-00805F9B34FB");
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private String diagState(String where) {
        return where
                + " started=" + started
                + " adapter=" + (bluetoothAdapter != null)
                + " enabled=" + (bluetoothAdapter != null && bluetoothAdapter.isEnabled())
                + " scanner=" + (scanner != null)
                + " scanning=" + scanning
                + " knownAddr=" + knownDeviceAddress;
    }

    public interface JsonListener {
        void onReady();                // notifications enabled
        void onJson(String jsonLine);  // a full JSON envelope line
        void onError(String msg);
    }

    private volatile JsonListener jsonListener;

    public void setJsonListener(JsonListener l) {
        this.jsonListener = l;
    }

    private BluetoothGattCharacteristic notifyChar;
    private BluetoothGattCharacteristic writeChar;


    private final Object rxLock = new Object();
    private final StringBuilder rxBuffer = new StringBuilder(2048);

//    private static final String PREFS_NAME = "weartak_ble_prefs";
//    private static final String KEY_DEVICE_ADDRESS = "device_address";

    public interface StatusListener {
        void onConnectionStatusChanged(boolean connected);
    }

    private Context appContext;
    private final Context baseContext;
    private final StatusListener statusListener;

    private ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt bluetoothGatt;

    private volatile boolean scanning = false;
    private volatile boolean connected = false;

    private String knownDeviceAddress;
    private volatile boolean started = false;

    private final java.util.LinkedHashMap<String, DiscoveredDevice> discovered = new java.util.LinkedHashMap<>();
    private volatile ScanCallback listScanCallback;

    public WearTakBleClient(Context context, StatusListener listener) {
        this.baseContext = context;
        this.statusListener = listener;
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
            //return n + " (" + address + ") RSSI=" + rssi;
            return n + " (" + address + ")";
        }
    }

    public interface ScanListener {
        void onDeviceFound(DiscoveredDevice device);
        void onScanFinished();
        void onScanError(String msg);
    }

    public interface SelectionListener {
        void onSelected(DiscoveredDevice device);
    }


    public synchronized void start() {
        if (started) {
            Log.d(TAG, "start() called but already started");
            return;
        }

        if (baseContext == null) {
            Log.w(TAG, "start() called with null Context; BLE disabled");
            return;
        }

        appContext = baseContext.getApplicationContext();
        if (appContext == null) {
            appContext = baseContext;
        }

        BluetoothManager bm = (BluetoothManager)
                appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) {
            Log.w(TAG, "BluetoothManager is null; BLE disabled");
            return;
        }

        bluetoothAdapter = bm.getAdapter();
        if (bluetoothAdapter == null) {
            Log.w(TAG, "BluetoothAdapter is null; BLE disabled");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Log.w(TAG, "Bluetooth is disabled on device; BLE disabled");
            // We still mark started so we don't re-run init; user can enable BT later.
        }

        scanner = bluetoothAdapter.getBluetoothLeScanner();

//        SharedPreferences prefs =
//                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
//        knownDeviceAddress = prefs.getString(KEY_DEVICE_ADDRESS, null);

        scheduler = Executors.newSingleThreadScheduledExecutor();

        started = true;
        Log.d(TAG, "WearTakBleClient started, knownDeviceAddress=" + knownDeviceAddress);

        // Kick off initial connect/scan only if adapter is present
        if (bluetoothAdapter.isEnabled()) {
//            connectOrScan();
            Log.i(TAG, "start(): initialized; waiting for user Scan/Select (no auto-connect)");
        }
    }

    public synchronized void stop() {
        Log.d(TAG, "stop()");
        if (!started) return;

        started = false;

        // Tear down BLE resources defensively
        try {
            if (scanner != null) {
                // if you track scanning flag, stopScan() here
            }
        } catch (Exception ignored) {}

        try {
            if (bluetoothGatt != null) {
                if (ActivityCompat.checkSelfPermission(baseContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    // TODO: Consider calling
                    //    ActivityCompat#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                    return;
                }
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            }
        } catch (Exception ignored) {}

        bluetoothGatt = null;
        scanner = null;
        bluetoothAdapter = null;

        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }

        setConnected(false);
    }

    public synchronized void scanForDevices(final ScanListener listener) {
        LogX.i(TAG, "scanForDevices(): ENTER " + diagState("scanForDevices"));
        if (!started) start();

        if (bluetoothAdapter == null) {
            LogX.w(TAG, "scanForDevices(): adapter is null " + diagState("scanForDevices"));
            if (listener != null) listener.onScanError("Bluetooth adapter null");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            LogX.w(TAG, "scanForDevices(): Bluetooth disabled " + diagState("scanForDevices"));
            if (listener != null) listener.onScanError("Bluetooth disabled");
            return;
        }

        if (scanner == null) {
            scanner = bluetoothAdapter.getBluetoothLeScanner();
        }
        if (scanner == null) {
            LogX.w(TAG, "scanForDevices(): No BLE scanner " + diagState("scanForDevices"));
            if (listener != null) listener.onScanError("No BLE scanner");
            return;
        }
        if (scanning) {
            LogX.w(TAG, "scanForDevices(): Already scanning " + diagState("scanForDevices"));
            if (listener != null) listener.onScanError("Already scanning");
            return;
        }

        discovered.clear();
        scanning = true;

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(COMPANION_SERVICE_UUID))
                .build();

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build();

        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            if (listener != null) listener.onScanError("Missing BLUETOOTH_SCAN permission");
            scanning = false;
            return;
        }

        listScanCallback = new ScanCallback() {

            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                if (!scanning || result == null || result.getDevice() == null) return;

                BluetoothDevice d = result.getDevice();
                int rssi = result.getRssi();

                String addr = d.getAddress();
                String name = null;
                try {
                    if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                            == PackageManager.PERMISSION_GRANTED) {
                        name = d.getName();
                    }
                } catch (Throwable ignored) {}

                // ScanRecord diagnostics (service UUIDs + local name as advertised)
                String advName = null;
                String serviceUuids = "null";
                try {
                    if (result.getScanRecord() != null) {
                        advName = result.getScanRecord().getDeviceName();
                        List<ParcelUuid> uuids = result.getScanRecord().getServiceUuids();
                        serviceUuids = (uuids != null) ? uuids.toString() : "[]";
                    }
                } catch (Throwable t) {
                    LogX.e(TAG, "scan: failed reading ScanRecord", t);
                }

                LogX.d(TAG, "scan: result cbType=" + callbackType
                        + " addr=" + addr
                        + " name=" + name
                        + " advName=" + advName
                        + " rssi=" + rssi
                        + " svcUuids=" + serviceUuids);

                if (addr == null) return;

                DiscoveredDevice dd = new DiscoveredDevice(name, addr, rssi);
                discovered.put(addr, dd);

                if (listener != null) listener.onDeviceFound(dd);
            }

            @Override
            public void onScanFailed(int errorCode) {
                scanning = false;
                if (listener != null) listener.onScanError("Scan failed: " + errorCode);
            }
        };

        LogX.i(TAG, "scanForDevices(): startScan filters=1 mode=LOW_POWER");
        scanner.startScan(Collections.singletonList(filter), settings, listScanCallback);

        // stop scan after 8 seconds
        scheduler.schedule(() -> {
            LogX.i(TAG, "scanForDevices(): timeout reached, stopping. discoveredCount=" + discovered.size());
            stopListingScan();
            if (listener != null) listener.onScanFinished();
        }, 8, TimeUnit.SECONDS);
    }

    private synchronized void stopListingScan() {
        LogX.i(TAG, "stopListingScan(): stopping scan. discoveredCount=" + discovered.size());
        if (!scanning) return;
        scanning = false;
        try {
            if (scanner != null && listScanCallback != null) {
                if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN)
                        == PackageManager.PERMISSION_GRANTED) {
                    scanner.stopScan(listScanCallback);
                }
            }
        } catch (Exception ignored) {}
        listScanCallback = null;
    }

    public synchronized java.util.List<DiscoveredDevice> getLastDiscoveredDevices() {
        return new java.util.ArrayList<>(discovered.values());
    }

    public synchronized void connectToAddress(String address) {
        if (bluetoothAdapter == null || address == null) return;
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
        if (device != null) connectToDevice(device);
    }

    public synchronized void selectDevice(DiscoveredDevice device) {
        if (device == null) return;
        knownDeviceAddress = device.address;
        SharedPreferences prefs =
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_DEVICE_ADDRESS, knownDeviceAddress).apply();
        connectToAddress(device.address);
    }


    public void checkImmediate() {
        if (!started) {
            Log.d(TAG, "checkImmediate() called before start(); ignoring");
            return;
        }
        if (scheduler != null) {
            scheduler.execute(this::connectOrScan);
        }
    }

    private void connectOrScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.w(TAG, "Bluetooth disabled");
            setConnected(false);
            return;
        }

        if (knownDeviceAddress != null) {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(knownDeviceAddress);
            if (device != null) {
                Log.d(TAG, "Trying reconnect to known device: " + describe(device));
                connectToDevice(device);
                return;
            }
        }

        startScan();
    }

    private void startScan() {
        if (scanner == null) {
            scanner = bluetoothAdapter != null ? bluetoothAdapter.getBluetoothLeScanner() : null;
        }
        if (scanner == null) {
            Log.w(TAG, "No BLE scanner");
            return;
        }
        if (scanning) {
            Log.d(TAG, "Already scanning");
            return;
        }

        Log.d(TAG, "Starting scan for WearTAK Event Service");

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(COMPANION_SERVICE_UUID))
                .build();

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build();

        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        scanning = true;

        // auto-stop scan after 20s
        scheduler.schedule(() -> {
            if (scanning && scanner != null) {
                Log.d(TAG, "Scan timeout, stopping");
                try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
                scanning = false;
                setConnected(false);
            }
        }, 20, TimeUnit.SECONDS);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            Log.d(TAG, "Scan result: " + describe(device));

            if (scanning && device != null) {
                scanning = false;
                try {
                    if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                        // TODO: Consider calling
                        //    ActivityCompat#requestPermissions
                        // here to request the missing permissions, and then overriding
                        //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                        //                                          int[] grantResults)
                        // to handle the case where the user grants the permission. See the documentation
                        // for ActivityCompat#requestPermissions for more details.
                        return;
                    }
                    scanner.stopScan(this); } catch (Exception ignored) {}
                Log.d(TAG, "Target found, connecting");
                connectToDevice(device);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "Scan failed: " + errorCode);
            scanning = false;
            setConnected(false);
        }
    };

    private void connectToDevice(BluetoothDevice device) {
        if (bluetoothGatt != null) {
            try {
                if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    // TODO: Consider calling
                    //    ActivityCompat#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                    return;
                }
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (Exception ignored) {}
            bluetoothGatt = null;
        }

        Log.d(TAG, "connectGatt to " + describe(device));
        bluetoothGatt = device.connectGatt(appContext, false, gattCallback);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt,
                                            int status,
                                            int newState) {
            Log.d(TAG, "onConnectionStateChange status=" + status + " newState=" + newState);

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "GATT connected, discovering services");
                setConnected(true);
                if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    // TODO: Consider calling
                    //    ActivityCompat#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                    return;
                }
                gatt.discoverServices();

                String addr = gatt.getDevice().getAddress();
                if (addr != null && !addr.equals(knownDeviceAddress)) {
                    knownDeviceAddress = addr;
                    SharedPreferences prefs =
                            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    prefs.edit().putString(KEY_DEVICE_ADDRESS, knownDeviceAddress).apply();
                    Log.d(TAG, "Saved device address: " + knownDeviceAddress);
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT disconnected");
                setConnected(false);
                if (bluetoothGatt != null) {
                    try {
                        bluetoothGatt.close();
                    } catch (Exception ignored) {}
                    bluetoothGatt = null;
                }
                // retry later
//                scheduler.schedule(WearTakBleClient.this::connectOrScan,
//                        5, TimeUnit.SECONDS);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG, "onServicesDiscovered status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) return;

            BluetoothGattService service = gatt.getService(COMPANION_SERVICE_UUID);
            if (service == null) {
                Log.w(TAG, "WearTAK Companion Service (A11A) not found on device");
                if (jsonListener != null) jsonListener.onError("Companion service missing (A11A)");
                return;
            }

            // Explicitly bind to the known WearTAK characteristics
            notifyChar = service.getCharacteristic(RX_FROM_COMPANION_UUID);
            writeChar  = service.getCharacteristic(TX_TO_COMPANION_UUID);

            if (notifyChar == null) {
                Log.w(TAG, "WearTAK RX characteristic (A11C) not found");
                if (jsonListener != null) jsonListener.onError("RX characteristic missing (A11C)");
                return;
            }

            if (writeChar == null) {
                // Not fatal if you only need RX, but log it so we know.
                Log.w(TAG, "WearTAK TX characteristic (A11B) not found");
            }

            BluetoothGattCharacteristic rx = service.getCharacteristic(RX_FROM_COMPANION_UUID);
            BluetoothGattCharacteristic tx = service.getCharacteristic(TX_TO_COMPANION_UUID);

            if (rx == null) { /* error */ return; }
            if (tx == null) { /* error */ return; }

            notifyChar = rx;          // notifications come from RX
            writeChar  = tx;          // store tx in a field if you want to send to watch

            // Find a characteristic that supports NOTIFY or INDICATE
            notifyChar = null;
            for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                int props = c.getProperties();
                boolean canNotify = (props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                boolean canIndicate = (props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;

                if (canNotify || canIndicate) {
                    notifyChar = c;
                    Log.d(TAG, "Using notify characteristic uuid=" + c.getUuid()
                            + " props=" + props);
                    break;
                }
            }

            if (notifyChar == null) {
                Log.w(TAG, "No NOTIFY/INDICATE characteristic found in Event Service");
                if (jsonListener != null) jsonListener.onError("No notify characteristic");
                return;
            }

            // Enable notifications
            if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            boolean notifSet = gatt.setCharacteristicNotification(notifyChar, true);
            if (!notifSet) {
                Log.w(TAG, "setCharacteristicNotification failed");
                if (jsonListener != null) jsonListener.onError("Enable notification failed");
                return;
            }

            BluetoothGattDescriptor cccd = notifyChar.getDescriptor(CCCD_UUID);
            if (cccd == null) {
                Log.w(TAG, "CCCD missing on characteristic " + notifyChar.getUuid());
                if (jsonListener != null) jsonListener.onError("CCCD missing");
                return;
            }

            if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                if (jsonListener != null) jsonListener.onError("Missing BLUETOOTH_CONNECT permission");
                return;
            }

            // Prefer NOTIFY if supported; otherwise INDICATE
            int props = notifyChar.getProperties();
            if ((props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            } else {
                cccd.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
            }

            boolean wrote = gatt.writeDescriptor(cccd);
            if (!wrote) {
                Log.w(TAG, "writeDescriptor(CCCD) failed");
                if (jsonListener != null) jsonListener.onError("CCCD write failed");
            }

        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (descriptor != null && CCCD_UUID.equals(descriptor.getUuid())) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Notifications enabled; ready to receive JSON");
                    if (jsonListener != null) jsonListener.onReady();
                } else {
                    Log.w(TAG, "CCCD write failed status=" + status);
                    if (jsonListener != null) jsonListener.onError("CCCD write failed: " + status);
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (characteristic == null) return;
            if (notifyChar == null) return;
            if (!characteristic.getUuid().equals(notifyChar.getUuid())) return;

            byte[] data = characteristic.getValue();
            if (data == null || data.length == 0) return;

            String chunk;
            try {
                chunk = new String(data, StandardCharsets.UTF_8);
            } catch (Throwable t) {
                if (jsonListener != null) jsonListener.onError("UTF-8 decode failed: " + t.getMessage());
                return;
            }

            // Assume newline-delimited JSON. If your watch uses a different framing, we’ll adjust.
            synchronized (rxLock) {
                rxBuffer.append(chunk);
                int idx;
                while ((idx = rxBuffer.indexOf("\n")) >= 0) {
                    String line = rxBuffer.substring(0, idx).trim();
                    rxBuffer.delete(0, idx + 1);
                    if (!line.isEmpty() && jsonListener != null) {
                        jsonListener.onJson(line);
                    }
                }
            }
        }


    };

    private void setConnected(boolean value) {
        if (connected != value) {
            connected = value;
            Log.d(TAG, "connected=" + connected);
            if (statusListener != null) {
                statusListener.onConnectionStatusChanged(connected);
            }
        }
    }

    private String describe(BluetoothDevice d) {
        if (d == null) return "null";
        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return "null";
        }
        String name = d.getName();
        String addr = d.getAddress();
        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2)
                ? d.getType() : -1;
        return "[name=" + name + ", addr=" + addr + ", type=" + type + "]";
    }
}
