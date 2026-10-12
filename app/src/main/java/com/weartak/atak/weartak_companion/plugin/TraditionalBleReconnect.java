package com.weartak.atak.weartak_companion.plugin;

/**
 * Traditional BLE only. Timers and transport callbacks run on the client's main thread.
 * Only a services-ready bonded peer can arm retries; never discovers or pairs a new identity.
 */
final class TraditionalBleReconnect {
    interface Transport {
        boolean available();
        boolean busy();
        boolean connect(String address);
        boolean probe();
        void reset();
    }

    private final SystemBondedWatchConnection.Scheduler scheduler;
    private final Transport transport;
    private boolean running, allowed, enabled, ready, retrying, awaiting;
    private String target;
    private long generation;
    private int failures, missed;
    private Runnable retry, timeout, health;

    TraditionalBleReconnect(SystemBondedWatchConnection.Scheduler scheduler, Transport transport) {
        this.scheduler = scheduler;
        this.transport = transport;
    }

    void start(boolean enabled, boolean allowed) {
        start(enabled, allowed, null);
    }

    void start(boolean enabled, boolean allowed, String previouslyReadyPeer) {
        stop();
        running = true;
        this.enabled = enabled;
        this.allowed = allowed;
        target = previouslyReadyPeer;
        resume();
    }

    void stop() {
        running = false;
        disarm();
    }

    void disarm() {
        target = null;
        ready = retrying = awaiting = false;
        failures = missed = 0;
        cancel();
    }

    void setAllowed(boolean allowed) {
        this.allowed = allowed;
        if (!allowed) disarm();
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        cancel();
        awaiting = retrying = false;
        failures = missed = 0;
        if (ready) scheduleHealth();
        else resume();
    }

    void onReady(String address) {
        cancel();
        target = address;
        ready = true;
        retrying = awaiting = false;
        failures = missed = 0;
        scheduleHealth();
    }

    void onLoss() {
        cancel();
        ready = awaiting = retrying = false;
        missed = 0;
        resume();
    }

    void pause() {
        cancel();
        ready = retrying = awaiting = false;
        failures = 0;
    }

    void resume() {
        if (!eligible() || ready || retrying || !transport.available()) return;
        retrying = true;
        scheduleRetry();
    }

    boolean isRetrying() { return retrying; }

    private boolean eligible() {
        return running && allowed && enabled && target != null;
    }

    private void scheduleRetry() {
        if (!eligible() || !transport.available()) {
            retrying = false;
            return;
        }
        cancel();
        final long token = generation;
        retry = () -> {
            if (!current(token) || !retrying) return;
            retry = null;
            if (!transport.available()) { pause(); return; }
            if (transport.busy()) { scheduleRetry(); return; }
            timeout = () -> {
                if (!current(token) || !retrying) return;
                timeout = null;
                transport.reset();
                onLoss();
            };
            scheduler.later(timeout, 30000);
            if (!transport.connect(target)) onLoss();
        };
        scheduler.later(retry, failures++ == 0 ? 2000 : 60000);
    }

    private void scheduleHealth() {
        if (!eligible() || !ready) return;
        final long token = generation;
        health = () -> {
            if (!current(token) || !ready) return;
            health = null;
            awaiting = true;
            timeout = () -> {
                if (!current(token) || !ready || !awaiting) return;
                timeout = null;
                awaiting = false;
                if (++missed >= 2) {
                    transport.reset();
                    onLoss();
                } else scheduleHealth();
            };
            scheduler.later(timeout, 10000);
            if (!transport.probe()) {
                transport.reset();
                onLoss();
            }
        };
        scheduler.later(health, 30000);
    }

    void onHealthResponse() {
        if (!eligible() || !ready || !awaiting) return;
        awaiting = false;
        missed = 0;
        if (timeout != null) scheduler.cancel(timeout);
        timeout = null;
        scheduleHealth();
    }

    private boolean current(long token) { return eligible() && token == generation; }

    private void cancel() {
        ++generation;
        if (retry != null) scheduler.cancel(retry);
        if (timeout != null) scheduler.cancel(timeout);
        if (health != null) scheduler.cancel(health);
        retry = timeout = health = null;
    }
}
