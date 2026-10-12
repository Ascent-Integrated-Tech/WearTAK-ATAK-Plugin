package com.weartak.atak.weartak_companion.plugin;

import android.content.SharedPreferences;

final class AutoReconnectPreference {
    // Do not silently import the experimental MR15 preference or any Samsung mode setting.
    static final String NAME = "wtk_traditional_reconnect_v1";
    private static final String KEY = "auto_reconnect_enabled";
    private final SharedPreferences prefs;

    AutoReconnectPreference(SharedPreferences prefs) { this.prefs = prefs; }
    boolean enabled() { return prefs.getBoolean(KEY, false); }
    void setEnabled(boolean enabled) { prefs.edit().putBoolean(KEY, enabled).apply(); }
    String armedAddress() { return prefs.getString("ready_peer_address", null); }
    void arm(String address) { prefs.edit().putString("ready_peer_address", address).apply(); }
    void disarm() { prefs.edit().remove("ready_peer_address").apply(); }
}
