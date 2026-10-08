package com.weartak.atak.weartak_companion.plugin;

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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private long sessionGeneration;
    private Runnable setupTimeout;
    private BluetoothGattCharacteristic txNotifyChar; // A11B
    private BluetoothGattCharacteristic rxWriteChar;  // A11C
    private static final String KEY_PREFERRED_ADDRESS = "preferred_address"; // saved when connected
    private volatile DiscoveredDevice pendingBondDevice;
    private volatile String pendingBondRecoveryName;
    private volatile boolean bondReceiverRegistered = false;

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
        void onTrustLost(String deviceAddress, String reason);
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

    private void clearPreferredAddress() {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().remove(KEY_PREFERRED_ADDRESS).apply();
    }

    public String getPreferredAddress() {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getString(KEY_PREFERRED_ADDRESS, null);
    }

    private void clearPreferredAddressIfMatches(String addr) {
        if (addr == null) return;
        String preferred = getPreferredAddress();
        if (preferred != null && preferred.equals(addr)) {
            clearPreferredAddress();
        }
    }

    private String currentGattAddress() {
        try {
            BluetoothGatt gatt = bluetoothGatt;
            if (gatt == null) return null;
            BluetoothDevice device = gatt.getDevice();
            return device != null ? device.getAddress() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isTrackedDeviceAddress(String address) {
        if (address == null) return false;

        DiscoveredDevice pending = pendingBondDevice;
        if (pending != null && address.equals(pending.address)) return true;

        String active = currentGattAddress();
        if (active != null && active.equals(address)) return true;

        String preferred = getPreferredAddress();
        return preferred != null && preferred.equals(address);
    }

    private void resetSessionAsync(String reason, long token) {
        runOnMain(() -> {
            if (token == sessionGeneration) resetSession(reason);
        });
    }

    private void failSecureSession(String msg, String reason) {
        final long token = sessionGeneration;
        error(msg);
        resetSessionAsync(reason, token);
    }

    private void handleTrustLoss(String address, String reason, String msg) {
        final long token = sessionGeneration;
        pendingBondDevice = null;
        clearPreferredAddressIfMatches(address);

        JsonListener l = jsonListener.get();
        if (l != null) l.onTrustLost(address, reason);

        error(msg);
        resetSessionAsync(reason + (address == null ? "" : (" " + address)), token);
    }

    private int bondStateOf(BluetoothDevice device) {
        try {
            return device.getBondState();
        } catch (SecurityException ignored) {
            return BluetoothDevice.BOND_NONE;
        } catch (Throwable ignored) {
            return BluetoothDevice.BOND_NONE;
        }
    }

    private boolean isBonded(BluetoothDevice device) {
        return device != null && bondStateOf(device) == BluetoothDevice.BOND_BONDED;
    }

    private void registerBondReceiver() {
        if (appContext == null || bondReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Bluetooth broadcasts originate from a privileged UID outside the app.
            appContext.registerReceiver(bondStateReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            appContext.registerReceiver(bondStateReceiver, filter);
        }
        bondReceiverRegistered = true;
    }

    private void unregisterBondReceiver() {
        if (appContext == null || !bondReceiverRegistered) return;
        try {
            appContext.unregisterReceiver(bondStateReceiver);
        } catch (Throwable ignored) {
        }
        bondReceiverRegistered = false;
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

    public interface BondedCompanionListener {
        void onFound(DiscoveredDevice device);
        void onUnavailable(String reason);
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

        registerBondReceiver();
        started = true;
        logI("start(): initialized (no auto-connect)");
    }

    public synchronized void stop() {
        stopScanInternal("stop()");
        closeGattInternal("stop()");
        unregisterBondReceiver();
        pendingBondDevice = null;
        pendingBondRecoveryName = null;
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
        pendingBondDevice = null;
        if (reason == null || !reason.startsWith("bond_recovery ")) {
            pendingBondRecoveryName = null;
        }
        setConnected(false);
    }

    // -------------------- scanning --------------------

    public synchronized void scanForDevices(final ScanListener listener) {
        if (!started) start();

        String unavailable = bluetoothUnavailableReason();
        if (unavailable != null) {
            failScan(listener, unavailable);
            return;
        }
        stopScanInternal("scanForDevices(): pre-stop");

        if (!hasScanPermission()) {
            failScan(listener, "Missing Bluetooth scan or location permission");
            return;
        }
        try {
            scanner = bluetoothAdapter.getBluetoothLeScanner();
        } catch (SecurityException e) {
            failScan(listener, "Missing Bluetooth permission");
            return;
        }
        if (scanner == null) {
            failScan(listener, "BLE scanner unavailable");
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
                main.post(() -> handleResult(result));
            }

            private void handleResult(ScanResult result) {
                if (activeScanCallback != this || !scanning || result == null
                        || result.getDevice() == null) return;

                BluetoothDevice d = result.getDevice();
                String addr = d.getAddress();
                if (addr == null) return;

                int rssi = result.getRssi();

                String name = safeGetDeviceName(d);
                String advName = safeGetAdvName(result);

                boolean hasService = safeHasServiceUuid(result, COMPANION_SERVICE_UUID);

                if (!hasService) return;

                String displayName = (advName != null && !advName.isEmpty()) ? advName : name;
                if (displayName == null) displayName = "WearTAK";
                StringBuilder strippedName = new StringBuilder(displayName);
                String[] parts = displayName.split("-");
                if (parts.length >= 2) {
                    strippedName = new StringBuilder(parts[1]);
                    for (int i = 2; i < parts.length; i++) strippedName.append("-").append(parts[i]);
                }
                Log.d(TAG, "SCAN: strippedName=(" + strippedName.toString() + "); displayName=(" + displayName + "); name=(" + name + "); advName=(" + advName + "); strippedName=(" + strippedName +")");
                DiscoveredDevice dd = new DiscoveredDevice(strippedName.toString(), addr, rssi);

                discovered.put(addr, dd);

                if (listener != null) listener.onDeviceFound(dd);
                Log.d(TAG, "SCAN: addr=" + addr + " name=" + name + " advName=" + advName
                        + " displayName=" + displayName + " hasService=" + hasService + " rssi=" + rssi);
            }

            @Override
            public void onScanFailed(int errorCode) {
                main.post(() -> {
                    if (activeScanCallback != this) return;
                    stopScanInternal("scan failed");
                    if (listener != null) listener.onScanError("Scan failed: " + errorCode);
                });
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
        scanning = false;

        clearScanTimeout();

        try {
            if (scanner != null && activeScanCallback != null) {
                if (hasScanPermission()) {
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

    public synchronized void findBondedCompanion(BondedCompanionListener listener) {
        if (listener == null) return;
        if (!started) start();
        String unavailable = bluetoothUnavailableReason();
        if (unavailable != null) {
            listener.onUnavailable(unavailable);
            return;
        }

        String preferredAddress = getPreferredAddress();
        if (preferredAddress != null) {
            try {
                BluetoothDevice preferred = bluetoothAdapter.getRemoteDevice(preferredAddress);
                if (isBonded(preferred)) {
                    listener.onFound(new DiscoveredDevice(safeGetDeviceName(preferred), preferredAddress, 0));
                    return;
                }
            } catch (IllegalArgumentException e) {
                logW("Saved companion address is invalid");
            }
        }

        AtomicBoolean completed = new AtomicBoolean(false);
        scanForDevices(new ScanListener() {
            @Override
            public void onDeviceFound(DiscoveredDevice device) {
                if (device == null || device.address == null || !isBondedAddress(device.address)
                        || !completed.compareAndSet(false, true)) {
                    return;
                }
                stopScanInternal("bonded companion found");
                savePreferredAddress(device.address);
                listener.onFound(device);
            }

            @Override
            public void onScanFinished(List<DiscoveredDevice> devices) {
                if (!completed.compareAndSet(false, true)) return;
                for (DiscoveredDevice device : devices) {
                    if (device != null && device.address != null && isBondedAddress(device.address)) {
                        savePreferredAddress(device.address);
                        listener.onFound(device);
                        return;
                    }
                }
                listener.onUnavailable("No paired WearTAK watch is reachable");
            }

            @Override
            public void onScanError(String msg) {
                if (completed.compareAndSet(false, true)) listener.onUnavailable(msg);
            }
        });
    }

    /**
     * Previously A11A-identified bond, independent of reachability. Android may report
     * BOND_NONE with its Bluetooth service powered off; retain the last confirmed bond
     * until the radio can verify it again (or an explicit bond-loss broadcast arrives).
     */
    public synchronized DiscoveredDevice getKnownBondedCompanion() {
        if (!started) start();
        if (bluetoothAdapter == null || !hasConnectPermission()) return null;
        String address = getPreferredAddress();
        if (address == null) return null;
        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
            if (!bluetoothAdapter.isEnabled() || device.getBondState() == BluetoothDevice.BOND_BONDED) {
                return new DiscoveredDevice(safeGetDeviceName(device), address, 0);
            }
            clearPreferredAddressIfMatches(address);
        } catch (IllegalArgumentException | SecurityException e) {
            logW("Unable to check known bonded WearTAK candidate");
        }
        return null;
    }

    private boolean isBondedAddress(String address) {
        try {
            return bluetoothAdapter != null && isBonded(bluetoothAdapter.getRemoteDevice(address));
        } catch (IllegalArgumentException e) {
            logW("Ignoring invalid Bluetooth address during companion discovery");
            return false;
        }
    }

    // -------------------- selection / connect --------------------

    public synchronized void connectToSelectedDevice(DiscoveredDevice device) {
        connectToSelectedDevice(device, true);
    }

    public synchronized void connectToSelectedDevice(DiscoveredDevice device, boolean allowBondCreation) {
        if (device == null || device.address == null) return;
        if (!started) start();

        closeGattInternal("new connection attempt");
        pendingBondDevice = null;
        setConnected(false);
        final long token = sessionGeneration;
        String unavailable = bluetoothUnavailableReason();
        if (unavailable != null) {
            error(unavailable);
            return;
        }

        stopScanInternal("connectToSelectedDevice()");

        BluetoothDevice remote;
        try {
            remote = bluetoothAdapter.getRemoteDevice(device.address);
        } catch (Throwable t) {
            error("Remote device lookup failed for address=" + device.address);
            return;
        }

        if (remote == null) {
            error("Remote device null for address=" + device.address);
            return;
        }

        if (!hasConnectPermission()) {
            error("Missing Bluetooth connect permission");
            return;
        }

        if (!isBonded(remote)) {
            if (!allowBondCreation) {
                error("System Bonded Watch requires an existing system bond.");
                return;
            }
            pendingBondDevice = device;
            boolean bondStarted;
            try {
                bondStarted = remote.createBond();
            } catch (SecurityException e) {
                pendingBondDevice = null;
                error("Unable to start BLE pairing: " + e.getMessage());
                return;
            }
            if (!bondStarted && !isBonded(remote)) {
                pendingBondDevice = null;
                error("Unable to start BLE pairing for " + device.address);
                return;
            }
            if (bondStarted) {
                logI("Bonding started for " + device.address + "; waiting for BOND_BONDED");
                return;
            }
        } else {
            logI("Reusing existing system bond for " + device.address);
        }

        pendingBondDevice = null;
        long now = android.os.SystemClock.uptimeMillis();
        long dt = now - lastGattCloseMs;
        long waitMs = (dt < RECONNECT_COOLDOWN_MS) ? (RECONNECT_COOLDOWN_MS - dt) : 0;

        if (waitMs > 0) {
            final DiscoveredDevice copy = device;
            main.postDelayed(() -> {
                if (started && token == sessionGeneration) connectGattNow(copy);
            }, waitMs);
        } else {
            connectGattNow(device);
        }
    }

    private synchronized void connectGattNow(DiscoveredDevice device) {
        if (device == null || device.address == null) return;

        String unavailable = bluetoothUnavailableReason();
        if (!started || unavailable != null) {
            error(unavailable != null ? unavailable : "BLE client stopped");
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
        if (!isBonded(d)) {
            handleTrustLoss(
                    device.address,
                    "bond_lost_before_connect",
                    "Device must be bonded before BLE connect");
            return;
        }

        if (!hasConnectPermission()) {
            error("Missing Bluetooth connect permission");
            return;
        }

        mtuNegotiated = false;
        negotiatedMtu = 23;
        final long token = sessionGeneration;
        setupTimeout = () -> {
            if (started && token == sessionGeneration && !connected) {
                failSecureSession("BLE setup timed out before transport ready", "setup_timeout");
            }
        };
        main.postDelayed(setupTimeout, 25000);
        try {
            bluetoothGatt = d.connectGatt(appContext, false, gattCallback);
            if (bluetoothGatt == null) failSecureSession("connectGatt returned null", "connect_failed");
        } catch (SecurityException | IllegalArgumentException e) {
            failSecureSession("BLE connection failed: " + e.getMessage(), "connect_failed");
        }
    }

    private boolean matchesNormalizedName(String expectedName, DiscoveredDevice device) {
        if (expectedName == null || device == null) return false;
        String candidate = normalizeWearTakName(device.name);
        return candidate != null && expectedName.equalsIgnoreCase(candidate);
    }

    private DiscoveredDevice findBestRecoveryTarget(List<DiscoveredDevice> devices, String expectedName) {
        if (devices == null || expectedName == null) return null;
        DiscoveredDevice best = null;
        for (DiscoveredDevice device : devices) {
            if (!matchesNormalizedName(expectedName, device)) continue;
            if (best == null || device.rssi > best.rssi) {
                best = device;
            }
        }
        return best;
    }

    private synchronized boolean scheduleBondRecoveryScan(DiscoveredDevice pending) {
        if (pending == null || pending.address == null) return false;

        String recoveryName = normalizeWearTakName(pending.name);
        if (recoveryName == null) return false;
        if (recoveryName.equalsIgnoreCase(pendingBondRecoveryName)) return false;

        pendingBondRecoveryName = recoveryName;
        resetSession("bond_recovery " + pending.address);
        final long token = sessionGeneration;
        logI("Rescanning once for " + recoveryName + " after bond failure");

        AtomicBoolean reconnectIssued = new AtomicBoolean(false);
        ScanListener recoveryListener = new ScanListener() {
            @Override
            public void onDeviceFound(DiscoveredDevice device) {
                if (!matchesNormalizedName(recoveryName, device)) return;
                if (!reconnectIssued.compareAndSet(false, true)) return;

                List<DiscoveredDevice> snapshot = stopScanInternal("bond recovery match found");
                DiscoveredDevice recovered = findBestRecoveryTarget(snapshot, recoveryName);
                if (recovered == null) recovered = device;
                final DiscoveredDevice recoveredDevice = recovered;
                logI("Recovered fresh advertising address " + recoveredDevice.address + " for " + recoveryName);
                main.post(() -> {
                    if (started && token == sessionGeneration) connectToSelectedDevice(recoveredDevice);
                });
            }

            @Override
            public void onScanFinished(List<DiscoveredDevice> devices) {
                if (!reconnectIssued.compareAndSet(false, true)) return;

                DiscoveredDevice recovered = findBestRecoveryTarget(devices, recoveryName);
                if (recovered != null) {
                    final DiscoveredDevice recoveredDevice = recovered;
                    logI("Bond recovery scan found fresh address " + recoveredDevice.address + " for " + recoveryName);
                    main.post(() -> {
                        if (started && token == sessionGeneration) connectToSelectedDevice(recoveredDevice);
                    });
                    return;
                }

                pendingBondRecoveryName = null;
                handleTrustLoss(
                        pending.address,
                        "bond_recovery_not_found",
                        "Unable to rediscover watch after bond reset");
            }

            @Override
            public void onScanError(String msg) {
                if (!reconnectIssued.compareAndSet(false, true)) return;

                pendingBondRecoveryName = null;
                handleTrustLoss(
                        pending.address,
                        "bond_recovery_scan_error",
                        "Bond recovery scan failed: " + msg);
            }
        };

        scanForDevices(recoveryListener);
        return true;
    }

    public synchronized void disconnect() {
        resetSession("disconnect()");
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

        if (!hasConnectPermission()) {
            error("Missing Bluetooth connect permission");
            return false;
        }
        BluetoothDevice device = bluetoothGatt.getDevice();
        if (!isBonded(device)) {
            handleTrustLoss(
                    device != null ? device.getAddress() : null,
                    "bond_lost_before_write",
                    "writeJsonLineToWatch: bonded device required");
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
                postTxNext();
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

        if (!hasConnectPermission()) {
            synchronized (txLock) {
                txQueue.clear();
                txInFlight = false;
            }
            error("TX aborted: missing Bluetooth connect permission");
            return;
        }

        BluetoothDevice device = bluetoothGatt.getDevice();
        if (!isBonded(device)) {
            synchronized (txLock) {
                txQueue.clear();
                txInFlight = false;
            }
            handleTrustLoss(
                    device != null ? device.getAddress() : null,
                    "bond_lost_during_tx",
                    "TX aborted: bonded device required");
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
            failSecureSession("TX writeCharacteristic returned false", "tx_write_start_failed");
        }
    }

    public synchronized boolean isConnected() { return connected; }

    private void postTxNext() {
        final long token = sessionGeneration;
        main.post(() -> {
            if (token == sessionGeneration) txSendNextLocked();
        });
    }

    private final BroadcastReceiver bondStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!started || intent == null) return;
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    failSecureSession("Bluetooth disabled", "bluetooth_off");
                }
                return;
            }
            if (intent == null || !BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }

            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null || device.getAddress() == null) return;

            int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
            int prevBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE);
            String address = device.getAddress();
            logI("Bond state changed for " + address + ": " + prevBondState + " -> " + bondState);

            DiscoveredDevice pending = pendingBondDevice;
            boolean pendingMatch = pending != null && pending.address != null
                    && pending.address.equals(address);
            if (bondState == BluetoothDevice.BOND_BONDED && pendingMatch) {
                pendingBondDevice = null;
                pendingBondRecoveryName = null;
                final long token = sessionGeneration;
                main.post(() -> {
                    if (started && token == sessionGeneration) connectToSelectedDevice(pending, true);
                });
                return;
            }

            if (bondState != BluetoothDevice.BOND_NONE || prevBondState == BluetoothDevice.BOND_NONE) {
                return;
            }

            if (pendingMatch && prevBondState == BluetoothDevice.BOND_BONDING) {
                if (scheduleBondRecoveryScan(pending)) {
                    return;
                }
                pendingBondRecoveryName = null;
                handleTrustLoss(address, "bond_cancelled", "BLE bond failed or was cancelled");
                return;
            }

            if (isTrackedDeviceAddress(address)) {
                handleTrustLoss(address, "bond_lost", "BLE bond lost for " + address);
            }
        }
    };

    // -------------------- GATT --------------------

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            dispatchGatt(gatt, () -> handleConnectionStateChange(gatt, status, newState));
        }

        private void handleConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failSecureSession("GATT connection failed: " + status, "gatt_status_" + status);
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!hasConnectPermission()) {
                    failSecureSession("Missing Bluetooth connect permission", "connect_permission_lost");
                    return;
                }

                if (!gatt.requestMtu(DESIRED_MTU) && !gatt.discoverServices()) {
                    failSecureSession("Service discovery could not start", "discovery_start_failed");
                }
                return;
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!connected) {
                    failSecureSession("Bonded watch disconnected during BLE setup", "setup_disconnected");
                    return;
                }
                closeGattInternal("STATE_DISCONNECTED status=" + status);
                setConnected(false);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            dispatchGatt(gatt, () -> handleMtuChanged(gatt, mtu, status));
        }

        private void handleMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            mtuNegotiated = (status == BluetoothGatt.GATT_SUCCESS);
            if (mtuNegotiated) negotiatedMtu = mtu;

            if (!hasConnectPermission()) {
                failSecureSession("Missing Bluetooth connect permission", "mtu_permission_lost");
                return;
            }

            if (!gatt.discoverServices()) {
                failSecureSession("Service discovery could not start", "discovery_start_failed");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            dispatchGatt(gatt, () -> handleServicesDiscovered(gatt, status));
        }

        private void handleServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failSecureSession("Service discovery failed: " + status, "service_discovery_failed_" + status);
                return;
            }

            BluetoothGattService service = gatt.getService(COMPANION_SERVICE_UUID);
            if (service == null) {
                failSecureSession("Companion service A11A missing", "service_missing");
                return;
            }

            txNotifyChar = service.getCharacteristic(TX_TO_COMPANION_UUID);
            rxWriteChar  = service.getCharacteristic(RX_FROM_COMPANION_UUID);

            if (txNotifyChar == null) {
                failSecureSession("TX notify char A11B missing", "tx_char_missing");
                return;
            }

            if (rxWriteChar == null) {
                failSecureSession("RX write char A11C missing", "rx_char_missing");
                return;
            }

            enableTxNotifications(gatt, txNotifyChar);
        }

        private void enableTxNotifications(BluetoothGatt gatt, BluetoothGattCharacteristic tx) {
            if (!hasConnectPermission()) {
                failSecureSession("Missing Bluetooth connect permission", "notify_permission_lost");
                return;
            }

            boolean ok = gatt.setCharacteristicNotification(tx, true);
            if (!ok) {
                failSecureSession("setCharacteristicNotification failed", "set_notification_failed");
                return;
            }

            BluetoothGattDescriptor cccd = tx.getDescriptor(CCCD_UUID);
            if (cccd == null) {
                failSecureSession("CCCD missing on A11B", "cccd_missing");
                return;
            }

            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            boolean wrote = gatt.writeDescriptor(cccd);
            if (!wrote) failSecureSession("writeDescriptor(CCCD) failed", "cccd_write_start_failed");
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            dispatchGatt(gatt, () -> handleDescriptorWrite(gatt, descriptor, status));
        }

        private void handleDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (descriptor == null) return;
            if (!CCCD_UUID.equals(descriptor.getUuid())) return;
            if (descriptor.getCharacteristic() != txNotifyChar || connected) return;

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (!hasConnectPermission() || !isBonded(gatt.getDevice())) {
                    failSecureSession("Existing system bond required", "bond_lost_before_ready");
                    return;
                }
                if (setupTimeout != null) main.removeCallbacks(setupTimeout);
                setupTimeout = null;
                savePreferredAddress(gatt.getDevice().getAddress());
                setConnected(true);
                JsonListener l = jsonListener.get();
                if (l != null) l.onReady();
            } else {
                failSecureSession("CCCD write failed: " + status, "cccd_write_failed_" + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic != null ? characteristic.getValue() : null;
            onCharacteristicChanged(gatt, characteristic, value);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic, byte[] value) {
            final byte[] data = value != null ? value.clone() : null;
            dispatchGatt(gatt, () -> handleCharacteristicChanged(characteristic, data));
        }

        private void handleCharacteristicChanged(BluetoothGattCharacteristic characteristic, byte[] data) {
            if (characteristic == null) return;
            if (txNotifyChar == null) return;
            if (!characteristic.getUuid().equals(txNotifyChar.getUuid())) return;

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
            dispatchGatt(gatt, () -> handleCharacteristicWrite(characteristic, status));
        }

        private void handleCharacteristicWrite(BluetoothGattCharacteristic characteristic, int status) {
            if (characteristic == null) return;
            if (rxWriteChar == null) return;
            if (!characteristic.getUuid().equals(rxWriteChar.getUuid())) return;

            if (status != BluetoothGatt.GATT_SUCCESS) {
                synchronized (txLock) {
                    txQueue.clear();
                    txInFlight = false;
                }
                failSecureSession("TX chunk write failed status=" + status, "tx_chunk_write_failed_" + status);
                return;
            }

            // send next chunk
            postTxNext();
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
        ++sessionGeneration;
        if (setupTimeout != null) main.removeCallbacks(setupTimeout);
        setupTimeout = null;
        BluetoothGatt closing = bluetoothGatt;
        bluetoothGatt = null;
        try {
            if (closing != null) {
                if (hasConnectPermission()) {
                    try { closing.disconnect(); } catch (Throwable ignored) {}
                }
                try { closing.close(); } catch (Throwable ignored) {}
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

    private boolean hasConnectPermission() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? Manifest.permission.BLUETOOTH_CONNECT : Manifest.permission.BLUETOOTH;
        return appContext != null && ActivityCompat.checkSelfPermission(
                appContext, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasScanPermission() {
        if (appContext == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_ADMIN)
                == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String bluetoothUnavailableReason() {
        if (bluetoothAdapter == null) return "Bluetooth adapter unavailable";
        if (!hasConnectPermission()) return "Missing Bluetooth connect permission";
        try {
            return bluetoothAdapter.isEnabled() ? null : "Bluetooth disabled";
        } catch (SecurityException e) {
            return "Missing Bluetooth connect permission";
        }
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == main.getLooper()) action.run();
        else main.post(action);
    }

    private void dispatchGatt(BluetoothGatt gatt, Runnable action) {
        main.post(() -> {
            if (!started || gatt == null || gatt != bluetoothGatt) return;
            try {
                action.run();
            } catch (SecurityException e) {
                failSecureSession("Bluetooth permission unavailable: " + e.getMessage(), "permission_lost");
            }
        });
    }

    private void storeConnectedDeviceName() {}

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
            if (!hasConnectPermission()) return null;
            return d.getName();
        } catch (Throwable ignored) {}
        return null;
    }

    private String safeGetDeviceAlias(BluetoothDevice d) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        try {
            if (!hasConnectPermission()) return null;
            return d.getAlias();
        } catch (Throwable ignored) {}
        return null;
    }

    /** User-assigned name, Android device/model name, and address for a bonded watch. */
    public synchronized String describeBondedWatch(String address) {
        String alias = null;
        String model = null;
        if (bluetoothAdapter != null && address != null) {
            try {
                BluetoothDevice d = bluetoothAdapter.getRemoteDevice(address);
                alias = safeGetDeviceAlias(d);
                model = safeGetDeviceName(d);
            } catch (IllegalArgumentException ignored) {}
        }
        return SystemBondedWatchConnection.describeBondedWatch(alias, model, address);
    }

    private String normalizeWearTakName(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.startsWith("WT-")) trimmed = trimmed.substring(3);
        return trimmed.trim().isEmpty() ? null : trimmed.trim();
    }

    private void logI(String msg) { android.util.Log.i(TAG, msg); }
    private void logW(String msg) { android.util.Log.w(TAG, msg); }
}
