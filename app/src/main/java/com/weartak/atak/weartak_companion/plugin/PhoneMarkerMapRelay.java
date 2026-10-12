package com.weartak.atak.weartak_companion.plugin;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.atakmap.android.importexport.CotEventFactory;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;

import java.util.IdentityHashMap;
import java.util.Map;
import org.json.JSONObject;
import org.json.JSONException;

final class PhoneMarkerMapRelay {
    static final String WATCH_IMPORT = "weartak_watch_marker";
    private static final String TAG = "WTK/PointRelay";
    private static final long ACK_TIMEOUT_MS = 10_000L;
    private static final String[] EVENTS = {MapEvent.ITEM_ADDED, MapEvent.ITEM_REFRESH,
            MapEvent.ITEM_PERSIST, MapEvent.ITEM_IMPORTED, MapEvent.ITEM_REMOVED};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PhoneMarkerRelay policy;
    private final WearTakBleClient client;
    private final Map<Marker, Runnable> markers = new IdentityHashMap<>();
    private final PointMapItem.OnPointChangedListener pointListener = item -> changed(item);
    private final MapItem.OnMetadataChangedListener metadataListener = (item, key) -> changed(item);
    private final MapItem.OnTypeChangedListener typeListener = this::changed;
    private final MapEventDispatcher.MapEventDispatchListener eventListener = this::onEvent;
    private MapEventDispatcher dispatcher;
    private MapView mapView;

    PhoneMarkerMapRelay(PhoneMarkerRelay policy, WearTakBleClient client) {
        this.policy = policy;
        this.client = client;
    }

    synchronized void start(MapView view) {
        stop();
        if (view == null || view.getMapEventDispatcher() == null) return;
        mapView = view;
        dispatcher = view.getMapEventDispatcher();
        for (String event : EVENTS) {
            if (MapEvent.ITEM_REMOVED.equals(event)) dispatcher.addMapEventListenerToBase(event, eventListener);
            else dispatcher.addMapEventListener(event, eventListener);
        }
        // Observe existing points for future edits; never send a startup snapshot.
        for (MapItem item : view.getRootGroup().getItemsRecursive()) attach(item);
    }

    synchronized void stop() {
        if (dispatcher != null) {
            for (String event : EVENTS) {
                if (MapEvent.ITEM_REMOVED.equals(event)) dispatcher.removeMapEventListenerFromBase(event, eventListener);
                else dispatcher.removeMapEventListener(event, eventListener);
            }
        }
        for (Marker marker : markers.keySet().toArray(new Marker[0])) detach(marker);
        dispatcher = null;
        mapView = null;
        policy.clear();
    }

    private synchronized void onEvent(MapEvent event) {
        if (dispatcher == null || event == null) return;
        MapItem item = event.getItem();
        if (!(item instanceof Marker)) return;
        if (MapEvent.ITEM_REMOVED.equals(event.getType())) {
            try {
                final String uid = item.getUID();
                final String token = policy.deletionToken(uid);
                final long session = client.getReadySession();
                boolean sent = policy.forwardDeletion(snapshot(item), session,
                            (json, writeSession) -> {
                                boolean written = client.writeJsonLineToWatch(json, writeSession);
                                if (!written) Log.w(TAG, "Point deletion write rejected");
                                return written;
                            });
                if (sent && token != null) {
                    handler.postDelayed(() -> {
                        if (policy.resolveDeletionAck(uid, token, session)) {
                            reportDeletion(uid, "not acknowledged");
                        }
                    }, ACK_TIMEOUT_MS);
                }
            } catch (JSONException | RuntimeException e) {
                Log.e(TAG, "Point deletion export failed", e);
            }
            detach((Marker) item);
            policy.removed(item.getUID());
            return;
        }

        attach(item);
        if (event.getExtras() != null && event.getExtras().getBoolean(WATCH_IMPORT, false)) {
            try {
                policy.watchImported(snapshot(item));
            } catch (RuntimeException e) {
                Log.e(TAG, "Cannot snapshot imported watch marker", e);
            }
        }
        changed(item);
    }

    boolean receiveDeletion(String json) {
        final JSONObject payload;
        final String uid, token;
        try {
            payload = new JSONObject(json).optJSONObject("payload");
            Object target = payload == null ? null : payload.opt("targetUid");
            Object revision = payload == null ? null : payload.opt("revision");
            if (!(target instanceof String) || !(revision instanceof String)
                    || ((String) target).trim().isEmpty() || ((String) target).length() > 256
                    || !PhoneMarkerRelay.validToken((String) revision)) {
                Log.w(TAG, "Ignoring invalid point deletion");
                return false;
            }
            uid = (String) target;
            token = (String) revision;
        } catch (JSONException e) {
            Log.w(TAG, "Ignoring invalid point deletion JSON", e);
            return false;
        }
        final long session = client.getReadySession();
        handler.post(() -> {
            synchronized (PhoneMarkerMapRelay.this) {
                if (mapView == null || session < 0 || session != client.getReadySession()) {
                    Log.w(TAG, "Ignoring point deletion from ended/unready connection");
                    return;
                }
                MapItem item = mapView.getRootGroup().deepFindUID(uid);
                boolean removed = false;
                try {
                    if (!(item instanceof Marker) || !markers.containsKey(item)
                            || !policy.acceptsDeletion(snapshot(item), token, session)) {
                        Log.d(TAG, "Point deletion ignored: unknown or changed revision");
                    } else {
                        removed = policy.applyLocalDeletion(snapshot(item), token, session,
                                item::removeFromGroup);
                        Log.d(TAG, "Point deletion applied=" + removed);
                    }
                } catch (RuntimeException e) {
                    Log.e(TAG, "Point deletion application failed", e);
                }
                try {
                    if (!client.writeJsonLineToWatch(PhoneMarkerRelay.deletionAck(uid, token, removed), session)) {
                        Log.w(TAG, "Point deletion ack write rejected");
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "Point deletion ack failed", e);
                }
            }
        });
        return true;
    }

    boolean receiveDeletionAck(String json) {
        final String uid, token, result;
        try {
            JSONObject payload = new JSONObject(json).optJSONObject("payload");
            Object target = payload == null ? null : payload.opt("targetUid");
            Object revision = payload == null ? null : payload.opt("revision");
            Object outcome = payload == null ? null : payload.opt("result");
            if (!(target instanceof String) || !(revision instanceof String) || !(outcome instanceof String)
                    || !PhoneMarkerRelay.validToken((String) revision)) {
                Log.w(TAG, "Ignoring invalid point deletion ack");
                return false;
            }
            uid = (String) target;
            token = (String) revision;
            result = (String) outcome;
        } catch (JSONException e) {
            Log.w(TAG, "Ignoring invalid point deletion ack JSON", e);
            return false;
        }
        if (!policy.resolveDeletionAck(uid, token, client.getReadySession())) {
            Log.w(TAG, "Ignoring unknown point deletion ack");
            return false;
        }
        reportDeletion(uid, PhoneMarkerRelay.ACK_APPLIED.equals(result) ? "applied" : "rejected");
        return true;
    }

    private void reportDeletion(String uid, String outcome) {
        Log.i(TAG, "Watch point deletion uid=" + uid + " outcome=" + outcome);
        handler.post(() -> {
            MapView view = mapView;
            if (view != null) {
                Toast.makeText(view.getContext(), "Watch delete " + outcome, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void attach(MapItem item) {
        if (!(item instanceof Marker) || markers.containsKey(item)) return;
        Marker marker = (Marker) item;
        markers.put(marker, null);
        marker.addOnPointChangedListener(pointListener);
        marker.addOnMetadataChangedListener(metadataListener);
        marker.addOnTypeChangedListener(typeListener);
    }

    private void detach(Marker marker) {
        Runnable pending = markers.remove(marker);
        if (pending != null) handler.removeCallbacks(pending);
        marker.removeOnPointChangedListener(pointListener);
        marker.removeOnMetadataChangedListener(metadataListener);
        marker.removeOnTypeChangedListener(typeListener);
    }

    private synchronized void changed(MapItem item) {
        if (dispatcher == null || !(item instanceof Marker) || !markers.containsKey(item)) return;
        Marker marker = (Marker) item;
        Runnable pending = markers.get(marker);
        if (pending != null) handler.removeCallbacks(pending);
        long expectedSession = client.getReadySession();
        Runnable send = () -> send(marker, expectedSession);
        markers.put(marker, send);
        // Coalesce point/detail mutations until ATAK finishes this import or edit.
        handler.post(send);
    }

    private synchronized void send(Marker marker, long expectedSession) {
        if (dispatcher == null || !markers.containsKey(marker) || marker.getGroup() == null) return;
        markers.put(marker, null);
        try {
            policy.forward(snapshot(marker), expectedSession, client.getReadySession(),
                    System.currentTimeMillis(), (json, session) -> {
                        boolean queued = client.writeJsonLineToWatch(json, session);
                        if (!queued) Log.w(TAG, "Phone marker write rejected uid=" + marker.getUID());
                        return queued;
                    });
        } catch (Exception e) {
            Log.e(TAG, "Phone marker export/write failed uid=" + marker.getUID(), e);
        }
    }

    private PhoneMarkerRelay.Snapshot snapshot(MapItem item) {
        if (!PhoneMarkerRelay.supportedType(item.getType())) return null;
        CotEvent event = CotEventFactory.createCotEvent(item);
        if (event == null || event.getCotPoint() == null) return null;
        CotPoint point = event.getCotPoint();
        StringBuilder xml = new StringBuilder();
        event.buildXml(xml);
        String fingerprint = event.getType() + "|" + event.getHow() + "|" + point.toString()
                + "|" + (event.getDetail() == null ? "" : event.getDetail().toString());
        boolean excluded = mapView == null || item == mapView.getSelfMarker()
                || (mapView.getSelfMarker() != null
                    && item.getUID().equals(mapView.getSelfMarker().getUID()))
                || item.getMetaBoolean("emergency", false)
                || item.hasMetaValue("emergency") || item.hasMetaValue("nevercot")
                || event.findDetail("__group") != null || event.findDetail("track") != null
                || event.findDetail("emergency") != null
                || !item.getUID().equals(event.getUID());
        return new PhoneMarkerRelay.Snapshot(event.getUID(), event.getType(), point.getLat(),
                point.getLon(), point.getHae(), point.getCe(), point.getLe(),
                event.getTime() == null ? -1 : event.getTime().getMilliseconds(),
                event.getStart() == null ? -1 : event.getStart().getMilliseconds(),
                event.getStale() == null ? -1 : event.getStale().getMilliseconds(),
                excluded, fingerprint, xml.toString());
    }
}
