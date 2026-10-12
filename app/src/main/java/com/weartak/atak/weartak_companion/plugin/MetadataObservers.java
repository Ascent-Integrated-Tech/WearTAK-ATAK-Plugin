package com.weartak.atak.weartak_companion.plugin;

import com.atakmap.android.maps.MapItem;

/** ATAK 5.8 only accepts metadata listeners scoped to a key, so observe the keys that affect exported CoT. */
final class MetadataObservers {
    private static final String[] KEYS = {"callsign", "title", "remarks", "team", "role", "color", "how",
            "state", "alertState", "active", "emergency.cancel", "time", "stale", "lastUpdateTime"};

    private MetadataObservers() {}

    static void add(MapItem item, MapItem.OnMetadataChangedListener listener) {
        for (String key : KEYS) item.addOnMetadataChangedListener(key, listener);
    }

    static void remove(MapItem item, MapItem.OnMetadataChangedListener listener) {
        for (String key : KEYS) item.removeOnMetadataChangedListener(key, listener);
    }
}
