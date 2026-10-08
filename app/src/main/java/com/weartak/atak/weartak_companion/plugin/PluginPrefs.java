package com.weartak.atak.weartak_companion.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.atakmap.android.maps.MapView;

/**
 * Plugin contexts resolve their data directory to the plugin package, which the ATAK process
 * cannot write, so preferences saved there are lost when ATAK restarts. Store them in ATAK's
 * own data directory instead.
 */
final class PluginPrefs {

    private static final String TAG = "WTK/PluginPrefs";

    private PluginPrefs() {}

    static SharedPreferences get(Context fallback, String name) {
        Context host = null;
        MapView mapView = MapView.getMapView();
        if (mapView != null && mapView.getContext() != null) {
            host = mapView.getContext().getApplicationContext();
            if (host == null) host = mapView.getContext();
        }
        if (host == null) {
            Log.w(TAG, "MapView host context unavailable; using plugin fallback context");
            host = fallback;
        }
        return host.getSharedPreferences(name, Context.MODE_PRIVATE);
    }
}
