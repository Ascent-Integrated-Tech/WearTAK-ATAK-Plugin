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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
    private BluetoothGattCharacteristic txNotifyChar; // A11B
    private BluetoothGattCharacteristic rxWriteChar;  // A11C
    private static final String KEY_PREFERRED_ADDRESS = "preferred_address"; // saved when connected
    private volatile DiscoveredDevice pendingBondDevice = null;
    private volatile DiscoveredDevice pendingReplacementDevice = null;
    private volatile String pendingBondRecoveryName = null;
    private volatile boolean bondReceiverRegistered = false;
    private final LinkedHashSet<String> pendingBondRemovals = new LinkedHashSet<>();

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

    // -------- opt-in auto-reconnect --------
    private static final String KEY_AUTO_RECONNECT = "auto_reconnect_enabled"; // default OFF
    private static final String KEY_PREFERRED_NAME = "preferred_name";          // saved when connected
    private static final String KEY_RECONNECT_ARMED = "reconnect_armed";        // true after a session was established
    private static final long[] RECONNECT_BACKOFF_MS = {2_000L, 5_000L, 10_000L, 20_000L, 40_000L, 60_000L};
    private static final long RECONNECT_SCAN_WINDOW_MS = 15_000L;
    private static final long RECONNECT_CONNECT_TIMEOUT_MS = 45_000L;

    private volatile boolean reconnectActive = false;
    private int reconnectAttempt = 0;
    private int reconnectGeneration = 0;
    private Runnable reconnectRunnable = null;
    private Runnable reconnectWatchdog = null;
    private volatile DiscoveredDevice lastConnectDevice = null;
    private volatile boolean adapterReceiverRegistered = false;

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
        prefs.edit()
                .remove(KEY_PREFERRED_ADDRESS)
                .remove(KEY_PREFERRED_NAME)
                .putBoolean(KEY_RECONNECT_ARMED, false)
                .apply();
        cancelReconnectLoop("preferred device cleared");
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

    private void resetSessionAsync(String reason) {
        main.post(() -> resetSession(reason));
    }

    private void failSecureSession(String msg, String reason) {
        final boolean eligible = connected || reconnectActive;
        error(msg);
        main.post(() -> {
            resetSession(reason);
            handleUnexpectedDisconnect(eligible, reason);
        });
    }

    private void handleTrustLoss(String address, String reason, String msg) {
        pendingBondDevice = null;
        disarmReconnect("trust lost: " + reason);
        clearPreferredAddressIfMatches(address);

        JsonListener l = jsonListener.get();
        if (l != null) l.onTrustLost(address, reason);

        error(msg);
        resetSessionAsync(reason + (address == null ? "" : (" " + address)));
    }

    private int bondStateOf(BluetoothDevice device) {
        try {
            return device.getBondState();
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
        appContext.registerReceiver(bondStateReceiver, filter);
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

    private void registerAdapterStateReceiver() {
        if (appContext == null || adapterReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        appContext.registerReceiver(adapterStateReceiver, filter);
        adapterReceiverRegistered = true;
    }

    private void unregisterAdapterStateReceiver() {
        if (appContext == null || !adapterReceiverRegistered) return;
        try {
            appContext.unregisterReceiver(adapterStateReceiver);
        } catch (Throwable ignored) {
        }
        adapterReceiverRegistered = false;
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
        registerBondReceiver();
        registerAdapterStateReceiver();
        started = true;
        logI("start(): initialized (no auto-connect)");

        // Opt-in only: resume reconnecting to the last watch if the previous session was
        // lost unexpectedly (or ATAK was restarted while connected) and the user enabled it.
        if (canAutoReconnect()) {
            startReconnectLoop("start()");
        }
    }

    public synchronized void stop() {
        cancelReconnectLoop("stop()");
        stopScanInternal("stop()");
        closeGattInternal("stop()");
        unregisterBondReceiver();
        unregisterAdapterStateReceiver();
        pendingBondDevice = null;
        pendingReplacementDevice = null;
        pendingBondRecoveryName = null;
        pendingBondRemovals.clear();
        started = false;
        bluetoothAdapter = null;
        scanner = null;
        setConnected(false);
    }

    public synchronized void resetSession(String reason) {
        logI("resetSession(): " + reason);
        cancelReconnectLoop("resetSession: " + reason);
        stopScanInternal("resetSession: " + reason);
        closeGattInternal("resetSession: " + reason);

        synchronized (rxLock) { rxBuffer.setLength(0); }
        discovered.clear();
        pendingBondDevice = null;
        pendingReplacementDevice = null;
        if (reason == null || !reason.startsWith("bond_recovery ")) {
            pendingBondRecoveryName = null;
        }
        pendingBondRemovals.clear();
        setConnected(false);
    }

    // -------------------- scanning --------------------

    public synchronized void scanForDevices(final ScanListener listener) {
        scanForDevices(listener, Math.max(1, timeoutSeconds) * 1000L);
    }

    private synchronized void scanForDevices(final ScanListener listener, long timeoutMs) {
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

                if (!hasService) return;

                String displayName = (advName != null && !advName.isEmpty()) ? advName : name;
                StringBuilder strippedName = new StringBuilder(displayName);
                assert displayName != null;
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
        main.postDelayed(scanTimeoutRunnable, Math.max(1000L, timeoutMs));
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

        if (unpairConflictingBondedWatchesIfNeeded(device)) {
            return;
        }

        if (!isBonded(remote)) {
            pendingBondDevice = device;
            boolean bondStarted = false;
            try {
                bondStarted = remote.createBond();
            } catch (Throwable t) {
                error("createBond failed: " + t.getMessage());
                return;
            }

            if (!bondStarted && !isBonded(remote)) {
                error("Unable to start BLE bond for " + device.address);
                return;
            }

            if (!bondStarted) {
                logI("Device already bonded, continuing to connect " + device.address);
            } else {
                logI("Bonding started for " + device.address + "; waiting for BOND_BONDED");
                return;
            }
        }

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

    private synchronized List<BluetoothDevice> getBondReplacementCandidates(DiscoveredDevice targetDevice) {
        List<BluetoothDevice> wearTakCandidates = new ArrayList<>();
        if (targetDevice == null || targetDevice.address == null) return wearTakCandidates;
        if (!started) start();

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            return wearTakCandidates;
        }
        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return wearTakCandidates;
        }

        Set<BluetoothDevice> bondedDevices;
        try {
            bondedDevices = bluetoothAdapter.getBondedDevices();
        } catch (Throwable ignored) {
            return wearTakCandidates;
        }
        if (bondedDevices == null || bondedDevices.isEmpty()) return wearTakCandidates;

        List<BluetoothDevice> otherBondedDevices = new ArrayList<>();
        String targetName = normalizeWearTakName(targetDevice.name);
        for (BluetoothDevice bonded : bondedDevices) {
            if (bonded == null) continue;
            String address = bonded.getAddress();
            if (address == null || address.equals(targetDevice.address)) continue;
            if (!isBonded(bonded)) continue;
            otherBondedDevices.add(bonded);
            if (!isWearTakBondCandidate(bonded)) continue;

            String bondedName = normalizeWearTakName(safeGetDeviceLabel(bonded));
            if (targetName != null && bondedName != null
                    && targetName.equalsIgnoreCase(bondedName)) {
                continue;
            }
            wearTakCandidates.add(bonded);
        }

        if (!wearTakCandidates.isEmpty()) {
            return wearTakCandidates;
        }
        if (otherBondedDevices.size() == 1) {
            wearTakCandidates.add(otherBondedDevices.get(0));
        }
        return wearTakCandidates;
    }

    private synchronized boolean unpairConflictingBondedWatchesIfNeeded(DiscoveredDevice targetDevice) {
        List<BluetoothDevice> candidates = getBondReplacementCandidates(targetDevice);
        if (candidates.isEmpty()) return false;

        pendingReplacementDevice = targetDevice;
        pendingBondRemovals.clear();
        clearPairedDeviceIdentity();

        boolean waitingForRemoval = false;
        for (BluetoothDevice bonded : candidates) {
            String address = bonded.getAddress();
            if (address == null || !isBonded(bonded)) continue;

            pendingBondRemovals.add(address);
            clearPreferredAddressIfMatches(address);

            boolean removalStarted = removeBondIfPossible(bonded, "connect_replace_watch");
            if (!removalStarted && isBonded(bonded)) {
                pendingBondRemovals.clear();
                pendingReplacementDevice = null;
                error("Unable to unpair existing watch " + safeGetDeviceLabel(bonded));
                return true;
            }
            if (!removalStarted) {
                pendingBondRemovals.remove(address);
                continue;
            }
            waitingForRemoval = true;
        }

        if (!waitingForRemoval) {
            pendingReplacementDevice = null;
            pendingBondRemovals.clear();
            return false;
        }

        logI("Waiting for old bonded watch removal before connecting " + targetDevice.address);
        return true;
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
        if (!getBondReplacementCandidates(pending).isEmpty()) return false;

        pendingBondRecoveryName = recoveryName;
        resetSession("bond_recovery " + pending.address);
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
                main.post(() -> connectToSelectedDevice(recoveredDevice));
            }

            @Override
            public void onScanFinished(List<DiscoveredDevice> devices) {
                if (!reconnectIssued.compareAndSet(false, true)) return;

                DiscoveredDevice recovered = findBestRecoveryTarget(devices, recoveryName);
                if (recovered != null) {
                    final DiscoveredDevice recoveredDevice = recovered;
                    logI("Bond recovery scan found fresh address " + recoveredDevice.address + " for " + recoveryName);
                    main.post(() -> connectToSelectedDevice(recoveredDevice));
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
        if (!isBonded(d)) {
            handleTrustLoss(
                    device.address,
                    "bond_lost_before_connect",
                    "Device must be bonded before BLE connect");
            return;
        }

        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            error("Missing BLUETOOTH_CONNECT permission");
            return;
        }

        lastConnectDevice = device;
        bluetoothGatt = d.connectGatt(appContext, false, gattCallback);
    }

    /** User-initiated disconnect: never triggers auto-reconnect. */
    public synchronized void disconnect() {
        disarmReconnect("manual disconnect");
        closeGattInternal("disconnect()");
        setConnected(false);
    }

    // -------------------- opt-in auto-reconnect --------------------

    private SharedPreferences prefs() {
        Context ctx = (appContext != null) ? appContext : baseContext;
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isAutoReconnectEnabled() {
        return prefs().getBoolean(KEY_AUTO_RECONNECT, false);
    }

    public synchronized void setAutoReconnectEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_AUTO_RECONNECT, enabled).apply();
        reconnectAttempt = 0;
        logI("setAutoReconnectEnabled(): " + enabled);
        if (!enabled) {
            cancelReconnectLoop("auto-reconnect disabled");
        } else {
            resumeAutoReconnect("auto-reconnect enabled");
        }
    }

    /** True while the plugin is waiting for / attempting an automatic reconnect. */
    public boolean isAutoReconnecting() {
        return reconnectActive;
    }

    /**
     * Restart the reconnect loop if it is eligible (opt-in enabled, last session was lost
     * unexpectedly, not currently connected or connecting). Safe to call at any time.
     */
    public synchronized void resumeAutoReconnect(String reason) {
        if (reconnectActive || connected || bluetoothGatt != null || scanning) return;
        if (pendingBondDevice != null || pendingReplacementDevice != null) return;
        if (!canAutoReconnect()) return;
        startReconnectLoop(reason);
    }

    /** Device currently connected (or being connected), if known. */
    public DiscoveredDevice getActiveDevice() {
        String address = currentGattAddress();
        if (address == null) return null;
        DiscoveredDevice last = lastConnectDevice;
        if (last != null && address.equals(last.address)) return last;
        return new DiscoveredDevice(null, address, 0);
    }

    private boolean isReconnectArmed() {
        return prefs().getBoolean(KEY_RECONNECT_ARMED, false);
    }

    private void armReconnect(String name) {
        SharedPreferences.Editor e = prefs().edit().putBoolean(KEY_RECONNECT_ARMED, true);
        if (name != null && !name.trim().isEmpty()) {
            e.putString(KEY_PREFERRED_NAME, name.trim());
        }
        e.apply();
    }

    private synchronized void disarmReconnect(String reason) {
        prefs().edit().putBoolean(KEY_RECONNECT_ARMED, false).apply();
        reconnectAttempt = 0;
        cancelReconnectLoop(reason);
    }

    private DiscoveredDevice loadReconnectTarget() {
        SharedPreferences p = prefs();
        String address = p.getString(KEY_PREFERRED_ADDRESS, null);
        String name = p.getString(KEY_PREFERRED_NAME, null);
        if (address == null && normalizeWearTakName(name) == null) return null;
        return new DiscoveredDevice(name, address, 0);
    }

    private boolean canAutoReconnect() {
        if (!started) return false;
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return false;
        if (!isAutoReconnectEnabled() || !isReconnectArmed()) return false;
        return loadReconnectTarget() != null;
    }

    /**
     * Called after the link was lost without the user asking for it.
     * @param eligible true if a session was established (or a reconnect loop was already running)
     */
    private synchronized void handleUnexpectedDisconnect(boolean eligible, String reason) {
        if (!eligible) return;
        if (!canAutoReconnect()) {
            if (reconnectActive) cancelReconnectLoop("not eligible after " + reason);
            return;
        }
        if (reconnectActive) {
            scheduleNextReconnect(reason);
        } else {
            startReconnectLoop(reason);
        }
    }

    private synchronized void startReconnectLoop(String reason) {
        if (!canAutoReconnect()) return;
        reconnectActive = true;
        logI("Auto-reconnect started: " + reason);
        scheduleNextReconnect(reason);
    }

    private synchronized void scheduleNextReconnect(String reason) {
        if (!reconnectActive) return;
        clearReconnectCallbacks();
        final int gen = ++reconnectGeneration;
        int idx = Math.min(reconnectAttempt, RECONNECT_BACKOFF_MS.length - 1);
        long delay = RECONNECT_BACKOFF_MS[idx];
        reconnectAttempt++;
        logI("Auto-reconnect attempt #" + reconnectAttempt + " in " + delay + "ms (" + reason + ")");
        reconnectRunnable = () -> runReconnectAttempt(gen);
        main.postDelayed(reconnectRunnable, delay);
    }

    private synchronized void cancelReconnectLoop(String reason) {
        boolean wasActive = reconnectActive;
        reconnectActive = false;
        reconnectGeneration++;
        clearReconnectCallbacks();
        if (wasActive) logI("Auto-reconnect cancelled: " + reason);
    }

    private synchronized void clearReconnectCallbacks() {
        if (reconnectRunnable != null) {
            main.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
        if (reconnectWatchdog != null) {
            main.removeCallbacks(reconnectWatchdog);
            reconnectWatchdog = null;
        }
    }

    private synchronized void onReconnectSessionReady() {
        reconnectAttempt = 0;
        if (!reconnectActive) return;
        reconnectActive = false;
        reconnectGeneration++;
        clearReconnectCallbacks();
        logI("Auto-reconnect succeeded");
    }

    private synchronized void runReconnectAttempt(final int gen) {
        if (gen != reconnectGeneration || !reconnectActive) return;
        reconnectRunnable = null;

        if (!canAutoReconnect()) {
            cancelReconnectLoop("preconditions no longer met");
            return;
        }
        if (connected || bluetoothGatt != null) {
            // A connection is already up or in progress; its callbacks decide what happens next.
            logI("Auto-reconnect attempt skipped: connection already active/in progress");
            return;
        }
        if (scanning || pendingBondDevice != null || pendingReplacementDevice != null) {
            scheduleNextReconnect("busy (scan/bond in progress)");
            return;
        }

        final DiscoveredDevice target = loadReconnectTarget();
        final String targetName = normalizeWearTakName(target.name);
        final AtomicBoolean issued = new AtomicBoolean(false);
        logI("Auto-reconnect scanning for " + target);

        scanForDevices(new ScanListener() {
            @Override
            public void onDeviceFound(DiscoveredDevice device) {
                if (gen != reconnectGeneration) return;
                if (target.address == null || !target.address.equals(device.address)) return;
                if (!issued.compareAndSet(false, true)) return;
                stopScanInternal("auto-reconnect match found");
                main.post(() -> connectForReconnect(gen, device));
            }

            @Override
            public void onScanFinished(List<DiscoveredDevice> devices) {
                if (gen != reconnectGeneration) return;
                if (!issued.compareAndSet(false, true)) return;
                DiscoveredDevice match = findReconnectMatch(devices, target, targetName);
                if (match != null) {
                    main.post(() -> connectForReconnect(gen, match));
                } else {
                    scheduleNextReconnect("watch not advertising");
                }
            }

            @Override
            public void onScanError(String msg) {
                if (gen != reconnectGeneration) return;
                if (!issued.compareAndSet(false, true)) return;
                if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                    cancelReconnectLoop("Bluetooth disabled");
                    return;
                }
                scheduleNextReconnect("scan error: " + msg);
            }
        }, RECONNECT_SCAN_WINDOW_MS);
    }

    /** Exact address first; otherwise the strongest already-bonded device advertising the same WearTAK name. */
    private DiscoveredDevice findReconnectMatch(List<DiscoveredDevice> devices, DiscoveredDevice target, String targetName) {
        if (devices == null) return null;
        for (DiscoveredDevice d : devices) {
            if (target.address != null && target.address.equals(d.address)) return d;
        }
        DiscoveredDevice best = null;
        for (DiscoveredDevice d : devices) {
            if (!matchesNormalizedName(targetName, d)) continue;
            BluetoothDevice remote;
            try {
                remote = bluetoothAdapter.getRemoteDevice(d.address);
            } catch (Throwable ignored) {
                continue;
            }
            if (!isBonded(remote)) continue; // never start a new pairing from the background loop
            if (best == null || d.rssi > best.rssi) best = d;
        }
        return best;
    }

    private synchronized void connectForReconnect(final int gen, DiscoveredDevice device) {
        if (gen != reconnectGeneration || !reconnectActive) return;
        if (connected || bluetoothGatt != null) return;
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            cancelReconnectLoop("Bluetooth disabled");
            return;
        }

        logI("Auto-reconnect connecting to " + device);
        reconnectWatchdog = () -> {
            synchronized (WearTakBleClient.this) {
                if (gen != reconnectGeneration || !reconnectActive) return;
                reconnectWatchdog = null;
                logW("Auto-reconnect connect timed out for " + device.address);
                closeGattInternal("auto-reconnect timeout");
                setConnected(false);
                scheduleNextReconnect("connect timeout");
            }
        };
        main.postDelayed(reconnectWatchdog, RECONNECT_CONNECT_TIMEOUT_MS);

        long now = android.os.SystemClock.uptimeMillis();
        long dt = now - lastGattCloseMs;
        long waitMs = (dt < RECONNECT_COOLDOWN_MS) ? (RECONNECT_COOLDOWN_MS - dt) : 0;
        main.postDelayed(() -> {
            synchronized (WearTakBleClient.this) {
                if (gen != reconnectGeneration || !reconnectActive) return;
                if (connected || bluetoothGatt != null) return;
                connectGattNow(device);
            }
        }, waitMs);
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

    private final BroadcastReceiver bondStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }

            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null || device.getAddress() == null) return;

            int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
            int prevBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE);
            String address = device.getAddress();
            logI("Bond state changed for " + address + ": " + prevBondState + " -> " + bondState);

            if (pendingBondRemovals.contains(address) && bondState == BluetoothDevice.BOND_NONE) {
                pendingBondRemovals.remove(address);
                clearPreferredAddressIfMatches(address);
                if (pendingBondRemovals.isEmpty()) {
                    finishPendingBondReplacement("bond_removed " + address);
                }
                return;
            }

            DiscoveredDevice pending = pendingBondDevice;
            boolean pendingMatch = pending != null && pending.address != null && pending.address.equals(address);

            if (bondState == BluetoothDevice.BOND_BONDED && pendingMatch) {
                pendingBondDevice = null;
                pendingBondRecoveryName = null;
                main.post(() -> connectToSelectedDevice(pending));
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

    private final BroadcastReceiver adapterStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                cancelReconnectLoop("Bluetooth disabled");
            } else if (state == BluetoothAdapter.STATE_ON) {
                synchronized (WearTakBleClient.this) {
                    if (!started || bluetoothAdapter == null) return;
                    scanner = bluetoothAdapter.getBluetoothLeScanner();
                    if (!connected && bluetoothGatt != null && canAutoReconnect()) {
                        // Stale handle from before the adapter cycled.
                        closeGattInternal("Bluetooth restarted");
                    }
                    resumeAutoReconnect("Bluetooth enabled");
                }
            }
        }
    };

    // -------------------- GATT --------------------

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setConnected(true);
                BluetoothDevice device = gatt.getDevice();
                if (device != null && device.getAddress() != null) {
                    savePreferredAddress(device.getAddress());
                    DiscoveredDevice last = lastConnectDevice;
                    boolean sameDevice = last != null && device.getAddress().equals(last.address);
                    armReconnect(sameDevice ? last.name : null);
                }

                if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    error("Missing BLUETOOTH_CONNECT permission");
                    return;
                }

                gatt.requestMtu(DESIRED_MTU);
                return;
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                boolean eligible = connected || reconnectActive;
                setConnected(false);
                closeGattInternal("STATE_DISCONNECTED status=" + status);
                handleUnexpectedDisconnect(eligible, "STATE_DISCONNECTED status=" + status);
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
            if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                error("Missing BLUETOOTH_CONNECT permission");
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
            if (descriptor == null) return;
            if (!CCCD_UUID.equals(descriptor.getUuid())) return;

            if (status == BluetoothGatt.GATT_SUCCESS) {
                onReconnectSessionReady();
                JsonListener l = jsonListener.get();
                if (l != null) l.onReady();
            } else {
                failSecureSession("CCCD write failed: " + status, "cccd_write_failed_" + status);
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
                failSecureSession("TX chunk write failed status=" + status, "tx_chunk_write_failed_" + status);
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

    private void clearPairedDeviceIdentity() {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .remove(KEY_PAIRED_UID)
                .remove(KEY_PAIRED_CALLSIGN)
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
            if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) return null;
            return d.getName();
        } catch (Throwable ignored) {}
        return null;
    }

    private String safeGetDeviceAlias(BluetoothDevice d) {
        try {
            if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) return null;
            return d.getAlias();
        } catch (Throwable ignored) {}
        return null;
    }

    private String safeGetDeviceLabel(BluetoothDevice d) {
        String alias = safeGetDeviceAlias(d);
        if (alias != null && !alias.trim().isEmpty()) return alias.trim();

        String name = safeGetDeviceName(d);
        if (name != null && !name.trim().isEmpty()) return name.trim();

        String address = d != null ? d.getAddress() : null;
        return address != null ? address : "Unknown device";
    }

    private boolean isWearTakBondCandidate(BluetoothDevice d) {
        if (d == null) return false;

        String address = d.getAddress();
        String preferred = getPreferredAddress();
        if (preferred != null && preferred.equals(address)) return true;

        String alias = safeGetDeviceAlias(d);
        if (startsWithWT(alias)) return true;

        String name = safeGetDeviceName(d);
        return startsWithWT(name);
    }

    private boolean removeBondIfPossible(BluetoothDevice device, String reason) {
        if (device == null) return false;
        try {
            Object out = BluetoothDevice.class.getMethod("removeBond").invoke(device);
            boolean removed = out instanceof Boolean && (Boolean) out;
            logI("removeBond(" + reason + "): " + safeGetDeviceLabel(device) + " -> " + removed);
            return removed;
        } catch (Throwable t) {
            logW("removeBond(" + reason + ") failed: " + t.getMessage());
            return false;
        }
    }

    private String normalizeWearTakName(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.startsWith("WT-")) trimmed = trimmed.substring(3);
        return trimmed.trim().isEmpty() ? null : trimmed.trim();
    }

    private synchronized void finishPendingBondReplacement(String reason) {
        DiscoveredDevice next = pendingReplacementDevice;
        pendingReplacementDevice = null;
        pendingBondRemovals.clear();
        if (next == null) return;

        logI("finishPendingBondReplacement(): " + reason + " -> " + next.address);
        main.post(() -> connectToSelectedDevice(next));
    }

    private boolean startsWithWT(String s) { return s != null && s.startsWith("WT-"); }

    private void logI(String msg) { android.util.Log.i(TAG, msg); }
    private void logW(String msg) { android.util.Log.w(TAG, msg); }
}
