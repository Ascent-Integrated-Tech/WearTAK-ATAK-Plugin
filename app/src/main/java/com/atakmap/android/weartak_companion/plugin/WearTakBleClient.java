package com.atakmap.android.weartak_companion.plugin;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
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

    private static final String TAG = "WearTakBleClient";

    // TODO: lock these UUIDs with the WearTAK team
    public static final UUID EVENT_SERVICE_UUID =
            UUID.fromString("0000A100-0000-1000-8000-00805F9B34FB"); // WearTAK Event Service

    private static final String PREFS_NAME = "weartak_ble_prefs";
    private static final String KEY_DEVICE_ADDRESS = "device_address";

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

    public WearTakBleClient(Context context, StatusListener listener) {
        this.baseContext = context;
        this.statusListener = listener;
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

        SharedPreferences prefs =
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        knownDeviceAddress = prefs.getString(KEY_DEVICE_ADDRESS, null);

        scheduler = Executors.newSingleThreadScheduledExecutor();

        started = true;
        Log.d(TAG, "WearTakBleClient started, knownDeviceAddress=" + knownDeviceAddress);

        // Kick off initial connect/scan only if adapter is present
        if (bluetoothAdapter.isEnabled()) {
            connectOrScan();
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
                .setServiceUuid(new ParcelUuid(EVENT_SERVICE_UUID))
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
                scheduler.schedule(WearTakBleClient.this::connectOrScan,
                        5, TimeUnit.SECONDS);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG, "onServicesDiscovered status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) return;

            BluetoothGattService service = gatt.getService(EVENT_SERVICE_UUID);
            if (service == null) {
                Log.w(TAG, "WearTAK Event Service not found on device");
            } else {
                Log.d(TAG, "WearTAK Event Service found");
            }
            // For V1 connection-status only, we don't need characteristics yet.
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
