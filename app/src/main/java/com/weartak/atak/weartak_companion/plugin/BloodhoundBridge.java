package com.weartak.atak.weartak_companion.plugin;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.atakmap.android.bloodhound.BloodHoundTool;
import com.atakmap.android.bloodhound.BloodHoundMapComponent;
import com.atakmap.android.maps.MapActivity;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.coremap.maps.coords.GeoPoint;

import org.json.JSONObject;
import org.json.JSONException;

import java.util.HashMap;
import java.util.Map;
import java.lang.reflect.Field;

/** Observes actual native link state, never HUD visibility or route navigation. */
final class BloodhoundBridge implements BloodhoundSync.Port {
    private static final String TAG = "WTK/Bloodhound";
    private static final String HOUNDING = "hounding";
    private final MapView mapView;
    private final WearTakBleClient ble;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BloodhoundSync sync = new BloodhoundSync(this);
    private final Map<MapItem, Boolean> observed = new HashMap<>();
    private final java.util.Set<MapItem> fallbackTargets = new java.util.HashSet<>();
    private volatile boolean applyingUpdate;
    private volatile boolean closed;
    private volatile long connectionGeneration;
    private final MapItem.OnMetadataChangedListener metadata = this::metadataChanged;
    private final MapItem.OnMetadataChangedListener callsign = (item, key) -> sourceChanged(item);
    private final PointMapItem.OnPointChangedListener pointChanged = this::sourceChanged;
    private final MapEventDispatcher.MapEventDispatchListener events = this::mapEvent;

    BloodhoundBridge(MapView mapView, WearTakBleClient ble) {
        this.mapView = mapView;
        this.ble = ble;
        MapEventDispatcher dispatcher = mapView.getMapEventDispatcher();
        dispatcher.addMapEventListenerToBase(MapEvent.ITEM_ADDED, events);
        dispatcher.addMapEventListenerToBase(MapEvent.ITEM_REMOVED, events);
        dispatcher.addMapEventListenerToBase(MapEvent.ITEM_IMPORTED, events);
        for (MapItem item : mapView.getRootGroup().getItemsRecursive()) observe(item);
    }

    boolean handleJson(String line) {
        try {
            JSONObject env = new JSONObject(line);
            String type = env.optString("msg_type");
            if (!"bloodhound_control".equals(type)
                    && !"bloodhound_control_result".equals(type)) return false;
            if (env.optJSONObject("payload") == null) {
                failure("Malformed Bloodhound control envelope: missing object payload");
                return true;
            }
            if (closed || !connected()) return true;
            long generation = connectionGeneration;
            main.post(() -> {
                if (closed || generation != connectionGeneration || !connected()) return;
                sync.receive(env);
                JSONObject p = env.optJSONObject("payload");
                if ("bloodhound_control".equals(type) && p != null) {
                    String id = p.optString("session_id");
                    String action = p.optString("action");
                    main.postDelayed(() -> {
                        if (!closed && generation == connectionGeneration)
                            sync.timeout(id, action);
                    }, 3000);
                }
            });
            return true;
        } catch (JSONException e) {
            Log.w(TAG, "Malformed BLE JSON envelope", e);
            failure("Malformed BLE JSON envelope: " + e.getMessage());
            return false;
        }
    }

    void disconnected() {
        connectionGeneration++;
        main.post(sync::disconnected);
    }

    void close() {
        closed = true;
        connectionGeneration++;
        main.removeCallbacksAndMessages(null);
        sync.disconnected();
        MapEventDispatcher dispatcher = mapView.getMapEventDispatcher();
        dispatcher.removeMapEventListenerFromBase(MapEvent.ITEM_ADDED, events);
        dispatcher.removeMapEventListenerFromBase(MapEvent.ITEM_REMOVED, events);
        dispatcher.removeMapEventListenerFromBase(MapEvent.ITEM_IMPORTED, events);
        synchronized (observed) {
            for (MapItem item : observed.keySet()) {
                item.removeOnMetadataChangedListener(HOUNDING, metadata);
                item.removeOnMetadataChangedListener("callsign", callsign);
                ((PointMapItem) item).removeOnPointChangedListener(pointChanged);
            }
            observed.clear();
            fallbackTargets.clear();
        }
    }

    private void observe(MapItem item) {
        if (!(item instanceof PointMapItem)) return;
        synchronized (observed) {
            if (observed.containsKey(item)) return;
            observed.put(item, item.getMetaBoolean(HOUNDING, false));
            item.addOnMetadataChangedListener(HOUNDING, metadata);
            item.addOnMetadataChangedListener("callsign", callsign);
            ((PointMapItem) item).addOnPointChangedListener(pointChanged);
        }
    }

    private void mapEvent(MapEvent event) {
        MapItem item = event.getItem();
        if (closed || item == null) return;
        if (MapEvent.ITEM_IMPORTED.equals(event.getType())) {
            synchronized (observed) {
                // Direct CoT ingress takes ownership from the companion snapshot.
                fallbackTargets.remove(item);
            }
            return;
        }
        if (MapEvent.ITEM_ADDED.equals(event.getType())) {
            observe(item);
        } else {
            item.removeOnMetadataChangedListener(HOUNDING, metadata);
            item.removeOnMetadataChangedListener("callsign", callsign);
            if (item instanceof PointMapItem)
                ((PointMapItem) item).removeOnPointChangedListener(pointChanged);
            Boolean wasTracking;
            synchronized (observed) {
                wasTracking = observed.remove(item);
                fallbackTargets.remove(item);
            }
            if (Boolean.TRUE.equals(wasTracking)) trackingChanged(item, false);
        }
    }

    private void metadataChanged(MapItem item, String key) {
        boolean tracking = item.getMetaBoolean(HOUNDING, false);
        synchronized (observed) {
            Boolean previous = observed.put(item, tracking);
            if (previous != null && previous == tracking) return;
        }
        trackingChanged(item, tracking);
    }

    private void trackingChanged(MapItem item, boolean tracking) {
        if (closed || !connected()) return;
        // Capture the edge before posting: native replacement removes then recreates links.
        long generation = connectionGeneration;
        JSONObject target = new JSONObject();
        try {
            target.put("target_uid", item.getUID());
            if (tracking) {
                GeoPoint point = ((PointMapItem) item).getPoint();
                target.put("target_callsign", item.getMetaString("callsign", item.getTitle()));
                target.put("lat", point.getLatitude());
                target.put("lon", point.getLongitude());
                if (!Double.isNaN(point.getAltitude())
                        && !Double.isInfinite(point.getAltitude())) {
                    target.put("hae", point.getAltitude());
                }

            }
        } catch (Exception e) {
            failure("Invalid native Bloodhound target: " + e.getMessage());
            return;
        }
        main.post(() -> {
            if (closed || generation != connectionGeneration) return;
            if (tracking) sync.nativeStarted(target);
            else sync.nativeStopped(item.getUID());
        });
    }

    private JSONObject snapshot(PointMapItem item) throws Exception {
        GeoPoint point = item.getPoint();
        JSONObject p = new JSONObject();
        p.put("target_uid", item.getUID());
        p.put("target_callsign", item.getMetaString("callsign", item.getTitle()));
        p.put("lat", point.getLatitude());
        p.put("lon", point.getLongitude());
        if (!Double.isNaN(point.getAltitude()) && !Double.isInfinite(point.getAltitude()))
            p.put("hae", point.getAltitude());
        return p;
    }

    private void publishUpdate(MapItem item) {
        if (closed || applyingUpdate || !connected()
                || !item.getMetaBoolean(HOUNDING, false)) return;
        long generation = connectionGeneration;
        try {
            JSONObject p = snapshot((PointMapItem) item);
            main.post(() -> {
                if (!closed && generation == connectionGeneration
                        && item.getMetaBoolean(HOUNDING, false)) sync.nativeUpdated(p);
            });
        } catch (Exception e) {
            failure("Invalid Bloodhound target update");
        }

    }

    private void sourceChanged(MapItem item) {
        if (applyingUpdate) return;
        synchronized (observed) {
            // CoT import changes the point before ITEM_IMPORTED is dispatched.
            // Local/manual source edits also take authority over the fallback.
            fallbackTargets.remove(item);
        }
        publishUpdate(item);
    }

    @Override public void update(JSONObject target) throws Exception {
        MapItem item = mapView.getRootGroup().deepFindUID(target.getString("target_uid"));
        synchronized (observed) {
            if (!fallbackTargets.contains(item)) return;
        }
        GeoPoint current = ((PointMapItem) item).getPoint();
        boolean start = "start".equals(target.optString("action"));
        double lat = start && !target.has("lat") ? current.getLatitude() : target.getDouble("lat");
        double lon = start && !target.has("lon") ? current.getLongitude() : target.getDouble("lon");
        double hae = target.optDouble("hae", start ? current.getAltitude() : GeoPoint.UNKNOWN);
        if (Double.isNaN(lat) || Double.isInfinite(lat) || Math.abs(lat) > 90
                || Double.isNaN(lon) || Double.isInfinite(lon) || Math.abs(lon) > 180
                || (target.has("hae") && (Double.isNaN(hae) || Double.isInfinite(hae))))
            throw new IllegalArgumentException("Invalid target coordinates");
        applyingUpdate = true;
        try {
            ((PointMapItem) item).setPoint(new GeoPoint(lat, lon, hae));
            if (target.has("target_callsign")) {
                String name = target.getString("target_callsign");
                item.setTitle(name);
                item.setMetaString("callsign", name);
            }
        } finally {
            applyingUpdate = false;
        }
    }

    @Override public boolean connected() {
        return !closed && ble.isBondedConnected();
    }

    @Override public boolean send(JSONObject envelope) {
        return connected() && ble.writeJsonLineToWatch(envelope.toString());
    }

    @Override public void awaitResult(String id, String action) {
        long generation = connectionGeneration;
        main.postDelayed(() -> {
            if (!closed && generation == connectionGeneration && connected())
                sync.resultTimeout(id, action);
        }, 5000);
    }

    @Override public boolean isTracking(String uid) {
        try {
            BloodHoundTool tool = nativeTool();
            PointMapItem item = tool.getEndItem();
            return Boolean.TRUE.equals(tool.getRunning()) && item != null
                    && uid.equals(item.getUID()) && item.getMetaBoolean(HOUNDING, false);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read native Bloodhound state", e);
        }
    }

    @Override public void start(JSONObject target) throws Exception {
        if (!connected()) throw new IllegalStateException("Bonded BLE connection required");
        BloodHoundTool tool = nativeTool();
        String uid = target.getString("target_uid");
        if (mapView.getSelfMarker() == null
                || mapView.getSelfMarker().getGroup() == null
                || uid.equals(mapView.getSelfMarker().getUID())) {
            throw new IllegalStateException("ATAK self marker unavailable or target is self");
        }
        MapItem item = mapView.getRootGroup().deepFindUID(uid);
        if (item == null) {
            double lat = target.getDouble("lat");
            double lon = target.getDouble("lon");
            double hae = target.optDouble("hae", GeoPoint.UNKNOWN);
            if (Double.isNaN(lat) || Double.isInfinite(lat) || Math.abs(lat) > 90
                    || Double.isNaN(lon) || Double.isInfinite(lon) || Math.abs(lon) > 180
                    || (target.has("hae") && (Double.isNaN(hae) || Double.isInfinite(hae)))) {
                throw new IllegalArgumentException("Invalid target coordinates");
            }
            Marker marker = new Marker(new GeoPoint(lat, lon, hae), uid);
            marker.setType("a-u-G");
            String callsign = target.optString("target_callsign", uid);
            marker.setTitle(callsign);
            marker.setMetaString("callsign", callsign);
            mapView.getRootGroup().addItem(marker);
            observe(marker);
            synchronized (observed) {
                fallbackTargets.add(marker);
            }
            item = marker;
        }
        if (!(item instanceof PointMapItem)) {
            throw new IllegalArgumentException("Target is not a point map item");
        }
        update(target);
        // Invoke the installed receiver synchronously: a queued UID toggle could stop
        // a different track if the user switches targets before the broadcast executes.
        tool.getHoundReceiver().onReceive(mapView.getContext(),
                new Intent(BloodHoundTool.BLOOD_HOUND).putExtra("uid", uid));
    }

    @Override public void stop(String uid) throws Exception {
        if (!connected() || !isTracking(uid)) return;
        BloodHoundTool tool = nativeTool();
        tool.getHoundReceiver().onReceive(mapView.getContext(),
                new Intent(BloodHoundTool.BLOOD_HOUND).putExtra("uid", uid));
    }

    private BloodHoundTool nativeTool() throws Exception {
        if (!(mapView.getContext() instanceof MapActivity)) {
            throw new IllegalStateException("ATAK map activity unavailable");
        }
        Object component = ((MapActivity) mapView.getContext())
                .getMapComponent(BloodHoundMapComponent.class);
        if (component == null) throw new IllegalStateException("Native Bloodhound unavailable");
        // ATAK 5.5 exposes the component registry and receiver, but no tool getter.
        Field field = BloodHoundMapComponent.class.getDeclaredField("_buttonTool");
        field.setAccessible(true);
        Object value = field.get(component);
        if (!(value instanceof BloodHoundTool)) {
            throw new IllegalStateException("Unsupported native Bloodhound SDK");
        }
        return (BloodHoundTool) value;
    }

    @Override public void failure(String message) {
        if (message == null) message = "Bloodhound synchronization failed";
        Log.w(TAG, message);
        String text = message;
        main.post(() -> {
            if (!closed) Toast.makeText(mapView.getContext(), text, Toast.LENGTH_LONG).show();
        });
    }
}
