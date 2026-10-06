package com.weartak.atak.weartak_companion.plugin;

/**
 * Explicit activation seam for a future connection-mode selector. All state and transport
 * calls run on the supplied main-thread scheduler. This route never creates or removes bonds.
 */
public final class SystemBondedWatchConnection {
    public interface Scheduler {
        void execute(Runnable action);
        void later(Runnable action, long delayMs);
        void cancel(Runnable action);
    }

    public interface Transport {
        void find(WearTakBleClient.BondedCompanionListener listener);
        void connect(WearTakBleClient.DiscoveredDevice device);
        void reset();
    }

    public interface Listener {
        void onStatus(String message);
        void onConnecting(WearTakBleClient.DiscoveredDevice device);
    }

    private final Scheduler scheduler;
    private final Transport transport;
    private final Listener listener;
    private boolean active;
    private boolean attempting;
    private boolean connecting;
    private boolean ready;
    private long generation;
    private long retryDelay = 2000;
    private Runnable retry;
    private Runnable timeout;
    private String status = "Automatic bonded-watch connection stopped";

    public SystemBondedWatchConnection(Scheduler scheduler, Transport transport, Listener listener) {
        this.scheduler = scheduler;
        this.transport = transport;
        this.listener = listener;
    }

    public void start() {
        scheduler.execute(() -> {
            if (active) return;
            active = true;
            retryDelay = 2000;
            attempt();
        });
    }

    /** Stops discovery, GATT setup, and retries, including already queued callbacks. */
    public void stop() {
        scheduler.execute(() -> {
            active = false;
            ++generation;
            cancelTimers();
            attempting = connecting = ready = false;
            transport.reset();
            updateStatus("Automatic bonded-watch connection stopped; use Scan for manual BLE");
        });
    }

    public void onConnectionChanged(boolean connected) {
        scheduler.execute(() -> {
            if (!active) return;
            if (connected) {
                if (!attempting || !connecting) return;
                cancelTimers();
                attempting = connecting = false;
                ready = true;
                retryDelay = 2000;
                updateStatus("CONNECTED (system bond, transport ready)");
            } else if (ready || connecting) {
                failed("Bonded watch disconnected");
            }
        });
    }

    public void onError(String message) {
        scheduler.execute(() -> {
            if (active && (attempting || ready)) failed(message);
        });
    }

    public String getStatus() { return status; }
    public boolean isActive() { return active; }

    private void attempt() {
        if (!active) return;
        retry = null;
        final long token = ++generation;
        attempting = true;
        connecting = ready = false;
        updateStatus("Finding existing system-bonded WearTAK watch...");
        armTimeout(token, 35000, "Bonded watch discovery timed out");
        transport.find(new WearTakBleClient.BondedCompanionListener() {
            @Override
            public void onFound(WearTakBleClient.DiscoveredDevice device) {
                scheduler.execute(() -> {
                    if (!current(token) || !attempting || connecting) return;
                    connecting = true;
                    updateStatus("Connecting bonded WearTAK watch...");
                    listener.onConnecting(device);
                    armTimeout(token, 25000, "BLE setup timed out before transport ready");
                    transport.connect(device);
                });
            }

            @Override
            public void onUnavailable(String reason) {
                scheduler.execute(() -> {
                    if (current(token) && attempting && !connecting) failed(reason);
                });
            }
        });
    }

    private boolean current(long token) { return active && token == generation; }

    private void armTimeout(long token, long delay, String reason) {
        if (timeout != null) scheduler.cancel(timeout);
        timeout = () -> {
            if (current(token) && attempting) failed(reason);
        };
        scheduler.later(timeout, delay);
    }

    private void failed(String reason) {
        final long token = ++generation;
        cancelTimers();
        attempting = connecting = ready = false;
        transport.reset();
        if (!active) return;
        long delay = retryDelay;
        retryDelay = Math.min(60000, retryDelay * 2);
        updateStatus(reason + "; retry in " + (delay / 1000) + "s");
        retry = () -> {
            if (current(token)) attempt();
        };
        scheduler.later(retry, delay);
    }

    private void cancelTimers() {
        if (retry != null) scheduler.cancel(retry);
        if (timeout != null) scheduler.cancel(timeout);
        retry = timeout = null;
    }

    private void updateStatus(String message) {
        status = message;
        listener.onStatus(message);
    }
}
