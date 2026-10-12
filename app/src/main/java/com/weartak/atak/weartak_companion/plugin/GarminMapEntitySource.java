package com.weartak.atak.weartak_companion.plugin;

import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.importexport.CotEventFactory;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Owns only map observers; emergency ingress uses the plugin's existing broadcast/CoT listener. */
final class GarminMapEntitySource implements GarminEntityForwarder.Source {
    private final MapView map;
    private final BleCotBridge origin;
    private final Set<PointMapItem> observed = new HashSet<>();
    private Consumer<Map<String, Object>> listener;
    private Consumer<String> removed;
    private final MapEventDispatcher.MapEventDispatchListener events = this::onEvent;
    private final MapItem.OnMetadataChangedListener metadata = (item, key) -> changed(item);
    private final MapItem.OnTypeChangedListener type = this::changed;
    private final PointMapItem.OnPointChangedListener point = this::changed;

    GarminMapEntitySource(MapView map, BleCotBridge origin) {
        this.map = map;
        this.origin = origin;
    }

    @Override public synchronized void subscribe(Consumer<Map<String, Object>> listener, Consumer<String> removed) {
        if (this.listener != null) return;
        this.listener = listener;
        this.removed = removed;
        for (String event : new String[]{MapEvent.ITEM_ADDED, MapEvent.ITEM_IMPORTED,
                MapEvent.ITEM_REMOVED}) map.getMapEventDispatcher().addMapEventListenerToBase(event, events);
        for (MapItem item : map.getRootGroup().getItemsRecursive()) observe(item);
    }

    @Override public synchronized void unsubscribe() {
        if (listener == null) return;
        listener = null;
        removed = null;
        for (String event : new String[]{MapEvent.ITEM_ADDED, MapEvent.ITEM_IMPORTED,
                MapEvent.ITEM_REMOVED}) map.getMapEventDispatcher().removeMapEventListenerFromBase(event, events);
        for (PointMapItem item : new ArrayList<>(observed)) unobserve(item);
    }

    private synchronized void observe(MapItem item) {
        if (listener == null || !(item instanceof PointMapItem) || !observed.add((PointMapItem) item)) return;
        item.addOnMetadataChangedListener(metadata);
        item.addOnTypeChangedListener(type);
        ((PointMapItem) item).addOnPointChangedListener(point);
    }

    private void unobserve(PointMapItem item) {
        observed.remove(item);
        item.removeOnMetadataChangedListener(metadata);
        item.removeOnTypeChangedListener(type);
        item.removeOnPointChangedListener(point);
    }

    private void onEvent(MapEvent event) {
        synchronized (this) {
            if (listener == null || event == null || event.getItem() == null) return;
        }
        MapItem item = event.getItem();
        if (MapEvent.ITEM_REMOVED.equals(event.getType())) {
            Consumer<String> removal;
            synchronized (this) {
                if (item instanceof PointMapItem) unobserve((PointMapItem) item);
                removal = removed;
            }
            if (removal != null) removal.accept(item.getUID());
            // Removal is not cancellation: Garmin has no paired-revision deletion contract.
            return;
        }
        observe(item);
        changed(item);
    }

    private void changed(MapItem item) {
        Consumer<Map<String, Object>> target;
        synchronized (this) {
            if (listener == null || !(item instanceof PointMapItem) || !observed.contains(item)
                    || item.getGroup() == null) return;
            target = listener;
        }
        Map<String, Object> entity = normalize(item);
        if (entity != null) target.accept(entity);
    }

    private Map<String, Object> normalize(MapItem item) {
        if (!(item instanceof PointMapItem) || item.getUID() == null || item == map.getSelfMarker()
                || item.hasMetaValue("nevercot")) return null;
        try {
            CotEvent event = CotEventFactory.createCotEvent(item);
            if (event == null || !item.getUID().equals(event.getUID())) return null;
            return normalize(event, item);
        } catch (RuntimeException e) {
            android.util.Log.w("WTK/GarminEntity", "Unable to normalize map entity", e);
            return null;
        }
    }

    void emergency(CotEvent event) {
        Consumer<Map<String, Object>> target;
        synchronized (this) { target = listener; }
        if (target == null || event == null || !PhoneEmergencyRelay.isEmergencyType(event.getType())) return;
        try {
            Map<String, Object> entity = normalize(event, null);
            if (entity != null) target.accept(entity);
        } catch (RuntimeException e) {
            android.util.Log.w("WTK/GarminEntity", "Unable to normalize emergency", e);
        }
    }

    private Map<String, Object> normalize(CotEvent event, MapItem item) {
        String uid = event.getUID();
        boolean self = map.getSelfMarker() != null
                && uid != null && uid.equals(map.getSelfMarker().getUID());
        boolean watch = origin != null && (origin.isWatchOriginEmergency(uid)
                || origin.isGarminOriginMarker(uid));
        Map<String, Object> meta = new HashMap<>();
        CotDetail contact = event.findDetail("contact"), group = event.findDetail("__group");
        CotDetail emergency = event.findDetail("emergency"), link = event.findDetail("link");
        put(meta, "callSign", attribute(contact, "callsign"));
        put(meta, "team", attribute(group, "name"));
        put(meta, "role", attribute(group, "role"));
        put(meta, "senderUID", attribute(link, "uid"));
        put(meta, "category", attribute(emergency, "type"));
        put(meta, "how", event.getHow());
        if (event.getTime() != null) meta.put("time", event.getTime().toString());
        if (event.getStart() != null) meta.put("start", event.getStart().toString());
        if (event.getStale() != null) meta.put("stale", event.getStale().toString());
        if (emergency != null) {
            meta.put("isAlert", true);
            meta.put("cancel", "true".equalsIgnoreCase(attribute(emergency, "cancel")));
        }
        if (item != null) {
            String revision = item.getMetaString("time", "");
            if (revision.isEmpty()) {
                long updated = item.getMetaLong("lastUpdateTime", 0);
                if (updated > 0) revision = java.time.Instant.ofEpochMilli(updated).toString();
            }
            // Exporters may synthesize new times; a map update's own revision wins.
            if (!revision.isEmpty()) meta.put("time", revision);
            String stale = item.getMetaString("stale", "");
            if (!stale.isEmpty()) meta.put("stale", stale);
            meta.put("state", item.getMetaString("state", ""));
            meta.put("alertState", item.getMetaString("alertState", ""));
            meta.put("active", item.getMetaBoolean("active", true));
            if (item.getMetaBoolean("emergency.cancel", false)) meta.put("cancel", true);
        }
        GeoPoint location = event.getGeoPoint();
        return GarminEntityAdapter.normalize(uid, location == null ? Double.NaN : location.getLatitude(),
                location == null ? Double.NaN : location.getLongitude(), event.getType(),
                meta, self && emergency == null && !PhoneEmergencyRelay.isEmergencyType(event.getType()), watch);
    }

    @Override public synchronized List<Map<String, Object>> snapshot(int limit) {
        if (listener == null || map.getSelfMarker() == null) return Collections.emptyList();
        GeoPoint self = map.getSelfMarker().getPoint();
        if (self == null || !self.isValid()
                || GarminEntityAdapter.expired(map.getSelfMarker().getMetaString("stale", "")))
            return Collections.emptyList();
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (MapItem item : map.getRootGroup().getItemsRecursive()) {
            Map<String, Object> entity = normalize(item);
            if (entity != null) candidates.add(entity);
        }
        return GarminEntityAdapter.nearest(candidates, self.getLatitude(), self.getLongitude(), limit);
    }

    private static String attribute(CotDetail detail, String key) {
        return detail == null ? null : detail.getAttribute(key);
    }
    private static void put(Map<String, Object> meta, String key, String value) {
        if (value != null && !value.trim().isEmpty()) meta.put(key, value);
    }
}
