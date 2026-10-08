package com.weartak.atak.weartak_companion.plugin;

/** Settings snapshots belong to a single BLE device/session, not just a transport mode. */
public final class SettingsSessionGuard {
    private volatile long generation;
    private boolean loaded;

    public long token() { return generation; }
    public boolean isCurrent(long token) { return token == generation; }
    public boolean canSend() { return loaded; }

    public void clear() {
        ++generation;
        loaded = false;
    }

    public boolean acceptSnapshot(long token, boolean complete) {
        if (!isCurrent(token)) return false;
        if (complete) loaded = true;
        return true;
    }
}
