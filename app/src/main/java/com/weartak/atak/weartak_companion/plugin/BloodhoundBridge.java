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
    interface Transport {
        boolean connected();
        boolean send(JSONObject envelope);
    }

    private final MapView mapView;
    private final Transport transport;
    private final boolean garminTransport;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BloodhoundSync sync = new BloodhoundSync(this);
    private final Map<MapItem, Boolean> observed = new HashMap<>();
    private volatile boolean closed;
    private volatile long connectionGeneration;
    private volatile String relaySession;
    private final MapItem.OnMetadataChangedListener metadata = this::metadataChanged;
    private final MapItem.OnMetadataChangedListener callsign = (item, key) -> sourceChanged(item);
    private final PointMapItem.OnPointChangedListener pointChanged = this::sourceChanged;
    private final MapEventDispatcher.MapEventDispatchListener events = this::mapEvent;

    BloodhoundBridge(MapView mapView, WearTakBleClient ble) {
        this(mapView, new Transport() {
            @Override public boolean connected() { return ble.isBondedConnected(); }
            @Override public boolean send(JSONObject envelope) {
                return ble.writeJsonLineToWatch(envelope.toString());
            }
        });
    }

    BloodhoundBridge(MapView mapView, Transport transport) {
        this(mapView, transport, false);
    }

    BloodhoundBridge(MapView mapView, Transport transport, boolean garminTransport) {
        this.mapView = mapView;
        this.transport = transport;
        this.garminTransport = garminTransport;
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
            receive(env, type);
            return true;
        } catch (JSONException e) {
            Log.w(TAG, "Malformed BLE JSON envelope", e);
            failure("Malformed BLE JSON envelope: " + e.getMessage());
            return false;
        }
    }

    boolean handleGarminMessage(JSONObject envelope) {
        String type = envelope == null ? "" : envelope.optString("msgType", "");
        if (!"bloodhound_control".equals(type)
                && !"bloodhound_control_result".equals(type)) return false;
        try {
            receive(GarminBloodhoundProtocol.inbound(envelope, relaySession), type);
        } catch (Exception e) {
            failure("Invalid Garmin Bloodhound message: " + e.getMessage());
        }
        return true;
    }

    void setRelaySession(String session) {
        if (!garminTransport || session == null || session.trim().isEmpty()
                || session.length() > 128 || !session.equals(session.trim())) return;
        for (int i = 0; i < session.length(); i++) {
            if (Character.isISOControl(session.charAt(i))) return;
        }
        if (!session.equals(relaySession)) {
            disconnected();
            relaySession = session;
        }
    }

    private void receive(JSONObject env, String type) {
        JSONObject payload = env.optJSONObject("payload");
        if (payload == null || closed || !connected()) return;
        final long generation = connectionGeneration;
        main.post(() -> {
            if (closed || generation != connectionGeneration || !connected()) return;
            sync.receive(env);
            if ("bloodhound_control".equals(type)) {
                String id = payload.optString("session_id");
                String action = payload.optString("action");
                main.postDelayed(() -> {
                    if (!closed && generation == connectionGeneration && connected())
                        sync.timeout(id, action);
                }, 3000);
            }
        });
    }

    void disconnected() {
        connectionGeneration++;
        main.post(sync::disconnected);
        if (garminTransport) relaySession = null;
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
        if (closed || !connected()
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
        publishUpdate(item);
    }

    @Override public void update(JSONObject target) throws Exception {
        String uid = target.getString("target_uid");
        MapItem item = mapView.getRootGroup().deepFindUID(uid);
        if (!(item instanceof PointMapItem))
            throw new IllegalArgumentException("Bloodhound target is not an existing point");
        GeoPoint point = ((PointMapItem) item).getPoint();
        if (point == null || !point.isValid()
                || Math.abs(point.getLatitude() - target.getDouble("lat")) > 0.000001d
                || Math.abs(point.getLongitude() - target.getDouble("lon")) > 0.000001d)
            throw new IllegalStateException("ATAK map owns Bloodhound target coordinates");
    }

    @Override public boolean connected() {
        return !closed && transport.connected() && (!garminTransport || relaySession != null);
    }

    @Override public boolean send(JSONObject envelope) {
        if (!connected()) return false;
        try {
            if (garminTransport && "bloodhound_control".equals(
                    envelope.optString("msg_type"))) {
                JSONObject payload = envelope.optJSONObject("payload");
                if (payload == null || relaySession == null) return false;
                payload.put("relaySession", relaySession);
                payload.put("bloodhoundVersion", 1);
                if (!payload.has("command_id"))
                    payload.put("command_id", java.util.UUID.randomUUID().toString());
            }
            return transport.send(envelope);
        } catch (JSONException e) {
            failure("Could not prepare Bloodhound message: " + e.getMessage());
            return false;
        } catch (IllegalArgumentException e) {
            failure("Invalid Bloodhound transport message: " + e.getMessage());
            return false;
        }
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
        if (!connected()) throw new IllegalStateException("Active watch connection required");
        BloodHoundTool tool = nativeTool();
        String uid = target.getString("target_uid");
        if (mapView.getSelfMarker() == null
                || mapView.getSelfMarker().getGroup() == null
                || uid.equals(mapView.getSelfMarker().getUID())) {
            throw new IllegalStateException("ATAK self marker unavailable or target is self");
        }
        MapItem item = mapView.getRootGroup().deepFindUID(uid);
        if (item == null) {
            throw new IllegalArgumentException("Bloodhound target is not present in the ATAK map");
        }
        if (!(item instanceof PointMapItem)) {
            throw new IllegalArgumentException("Target is not a point map item");
        }
        GeoPoint point = ((PointMapItem) item).getPoint();
        if (point == null || !point.isValid())
            throw new IllegalArgumentException("Bloodhound target has no valid ATAK location");
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
