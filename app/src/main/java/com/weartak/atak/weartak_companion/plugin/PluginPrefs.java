package com.weartak.atak.weartak_companion.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import com.atakmap.android.maps.MapView;

final class PluginPrefs {
    private PluginPrefs() {}

    static SharedPreferences get(Context fallback, String name) {
        MapView map = MapView.getMapView();
        return get(fallback, map == null ? null : map.getContext(), name);
    }

    static SharedPreferences get(Context fallback, Context host, String name) {
        // Plugin package data may be unwritable to ATAK's UID. Use the host's data directory.
        Context owner = host == null ? fallback : host;
        Context app = owner.getApplicationContext();
        return (app == null ? owner : app).getSharedPreferences(name, Context.MODE_PRIVATE);
    }
}
