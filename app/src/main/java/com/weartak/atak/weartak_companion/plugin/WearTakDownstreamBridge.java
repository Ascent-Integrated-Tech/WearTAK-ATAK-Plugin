package com.weartak.atak.weartak_companion.plugin;

import android.os.Bundle;
import android.util.Log;

import com.atakmap.android.chat.ChatManagerMapComponent;
import com.atakmap.android.emergency.EmergencyAlertReceiver;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.routes.Route;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Observes ATAK-side data and sends supported downstream envelopes to WearTAK.
 *
 * Routes and Bloodhound are intentionally plugin-side transport only in this
 * epic. The watch app is not changed here; its future receiver can consume the
 * route and bloodhound envelope types when that work is scheduled.
 */
final class WearTakDownstreamBridge implements
        MapEventDispatcher.MapEventDispatchListener,
        ChatManagerMapComponent.ChatMessageListener,
        EmergencyAlertReceiver.OnAlertChangedListener {

    interface Sender {
        boolean send(String json);
    }

    private static final String TAG = "WTK/Downstream";
    private static final String MSG_MARKER = "marker_downstream";
    private static final String MSG_ROUTE = "route";
    private static final String MSG_BLOODHOUND = "bloodhound";
    private static final String MSG_CHAT = "chat_downstream";
    private static final String MSG_EMERGENCY = "emergency_downstream";
    private static final String OP_CREATE = "create";
    private static final String OP_DELETE = "delete";
    private static final int MAX_SNAPSHOT_ITEMS = 100;
    private static final int MAX_TRACKED_MESSAGES = 512;
    private static final boolean SEND_DEFERRED_TYPES = false;

    private final MapView mapView;
    private final Sender sender;
    private final Map<String, String> lastSentPayloads = new LinkedHashMap<>();

    WearTakDownstreamBridge(MapView mapView, Sender sender) {
        this.mapView = mapView;
        this.sender = sender;
    }

    boolean start() {
        if (mapView == null) {
            Log.w(TAG, "Cannot start downstream bridge without MapView");
            return false;
        }
        MapEventDispatcher dispatcher = mapView.getMapEventDispatcher();
        dispatcher.addMapEventListener(MapEvent.ITEM_ADDED, this);
        dispatcher.addMapEventListener(MapEvent.ITEM_REMOVED, this);
        dispatcher.addMapEventListener(MapEvent.ITEM_PERSIST, this);
        dispatcher.addMapEventListener(MapEvent.ITEM_SHARED, this);
        dispatcher.addMapEventListener(MapEvent.ITEM_REFRESH, this);

        ChatManagerMapComponent chat = ChatManagerMapComponent.getInstance();
        if (chat != null) chat.addChatMessageListener(this);

        EmergencyAlertReceiver alerts = EmergencyAlertReceiver.getInstance();
        if (alerts != null) alerts.addOnAlertChangedListener(this);
        return true;
    }

    void stop() {
        if (mapView == null) return;
        MapEventDispatcher dispatcher = mapView.getMapEventDispatcher();
        dispatcher.removeMapEventListener(MapEvent.ITEM_ADDED, this);
        dispatcher.removeMapEventListener(MapEvent.ITEM_REMOVED, this);
        dispatcher.removeMapEventListener(MapEvent.ITEM_PERSIST, this);
        dispatcher.removeMapEventListener(MapEvent.ITEM_SHARED, this);
        dispatcher.removeMapEventListener(MapEvent.ITEM_REFRESH, this);

        ChatManagerMapComponent chat = ChatManagerMapComponent.getInstance();
        if (chat != null) chat.removeChatMessageListener(this);

        EmergencyAlertReceiver alerts = EmergencyAlertReceiver.getInstance();
        if (alerts != null) alerts.removeOnAlertChangedListener(this);
        synchronized (lastSentPayloads) {
            lastSentPayloads.clear();
        }
    }

    void sendInitialMarkerSnapshot() {
        if (mapView == null || mapView.getRootGroup() == null) return;
        synchronized (lastSentPayloads) {
            lastSentPayloads.entrySet().removeIf(entry -> entry.getKey().startsWith(MSG_MARKER + ":"));
        }
        final int[] count = {0};
        mapView.getRootGroup().deepForEachItem(item -> {
            if (count[0] >= MAX_SNAPSHOT_ITEMS) return false;
            if (item instanceof Route) {
                sendRoute((Route) item, OP_CREATE);
                return true;
            }
            if (sendMarker(item, OP_CREATE)) count[0]++;
            return count[0] < MAX_SNAPSHOT_ITEMS;
        });
        if (count[0] >= MAX_SNAPSHOT_ITEMS) {
            Log.w(TAG, "Marker snapshot capped at " + MAX_SNAPSHOT_ITEMS + " items");
        }
    }

    @Override
    public void onMapEvent(MapEvent event) {
        if (event == null || event.getItem() == null) return;
        MapItem item = event.getItem();
        if (item.getUID() != null && WatchOriginRegistry.isMarker(item.getUID())) {
            if (MapEvent.ITEM_REMOVED.equals(event.getType())) {
                WatchOriginRegistry.consumeMarker(item.getUID());
            }
            return;
        }
        String operation = operationFor(event.getType());
        if (item instanceof Route) {
            sendRoute((Route) item, operation);
        } else {
            sendMarker(item, operation);
        }
    }

    @Override
    public void chatMessageReceived(Bundle message) {
        if (message == null) return;
        String messageUid = firstNonBlank(message.getString("uid"), message.getString("messageId"));
        if (WatchOriginRegistry.consumeChat(messageUid)) return;
        JSONObject payload = new JSONObject();
        put(payload, "uid", messageUid);
        put(payload, "senderUid", message.getString("senderUid", ""));
        put(payload, "senderCallsign", message.getString("senderCallsign", ""));
        put(payload, "roomUid", message.getString("conversationId", "All Chat Rooms"));
        put(payload, "roomTitle", message.getString("conversationName", "All Chat Rooms"));
        put(payload, "message", message.getString("message", ""));
        put(payload, "sentTime", message.getLong("sentTime", System.currentTimeMillis()));
        sendEnvelope(MSG_CHAT, payload, messageUid);
    }

    @Override
    public void onAlertAdded(EmergencyAlertReceiver.EmergencyAlert alert) {
        if (isWatchOriginatedEmergency(alert, false)) return;
        sendEmergency(alert, "ALERT");
    }

    @Override
    public void onAlertRemoved(EmergencyAlertReceiver.EmergencyAlert alert) {
        if (isWatchOriginatedEmergency(alert, true)) return;
        sendEmergency(alert, "CANCEL");
    }

    private boolean sendMarker(MapItem item, String operation) {
        if (!isSupportedMarker(item)) return false;
        GeoPoint point = item instanceof PointMapItem ? ((PointMapItem) item).getPoint() : null;
        if (point == null && !OP_DELETE.equals(operation)) return false;

        if (point != null) sendBloodhoundState(item, point);

        JSONObject payload = new JSONObject();
        put(payload, "operation", operation);
        put(payload, "uid", item.getUID());
        put(payload, "type", item.getType());
        put(payload, "title", item.getTitle());
        if (point != null) {
            put(payload, "lat", point.getLatitude());
            put(payload, "lon", point.getLongitude());
            put(payload, "hae", point.getAltitude());
        }
        put(payload, "callsign", item.getMetaString("callsign", ""));
        put(payload, "remarks", item.getRemarks());
        return sendEnvelope(MSG_MARKER, payload, item.getUID());
    }

    private void sendBloodhoundState(MapItem item, GeoPoint point) {
        if (!SEND_DEFERRED_TYPES) return;
        if (!item.hasMetaValue("bloodhoundEta")) return;
        JSONObject payload = new JSONObject();
        put(payload, "operation", "update");
        put(payload, "uid", item.getUID());
        put(payload, "eta", item.getMetaDouble("bloodhoundEta", Double.NaN));
        put(payload, "lat", point.getLatitude());
        put(payload, "lon", point.getLongitude());
        sendEnvelope(MSG_BLOODHOUND, payload, item.getUID());
    }

    private void sendRoute(Route route, String operation) {
        if (!SEND_DEFERRED_TYPES) return;
        if (route == null || route.getUID() == null) return;
        JSONArray points = new JSONArray();
        for (int i = 0; i < route.getNumPoints(); i++) {
            GeoPointMetaData metadata = route.getPoint(i);
            if (metadata == null || metadata.get() == null) continue;
            GeoPoint point = metadata.get();
            JSONObject routePoint = new JSONObject();
            put(routePoint, "lat", point.getLatitude());
            put(routePoint, "lon", point.getLongitude());
            put(routePoint, "hae", point.getAltitude());
            append(points, routePoint);
        }

        JSONObject payload = new JSONObject();
        put(payload, "operation", operation);
        put(payload, "uid", route.getUID());
        put(payload, "title", route.getTitle());
        put(payload, "points", points);
        sendEnvelope(MSG_ROUTE, payload, route.getUID());
    }

    private void sendEmergency(EmergencyAlertReceiver.EmergencyAlert alert, String state) {
        if (alert == null) return;
        PointMapItem item = alert.getItem();
        GeoPoint point = alert.getPoint();
        JSONObject payload = new JSONObject();
        put(payload, "state", state);
        put(payload, "uid", item == null ? "" : item.getUID());
        put(payload, "message", alert.getMessage());
        put(payload, "category", alertCategory(alert));
        put(payload, "priority", alertPriority(alert));
        if (point != null) {
            put(payload, "lat", point.getLatitude());
            put(payload, "lon", point.getLongitude());
            put(payload, "hae", point.getAltitude());
        }
        sendEnvelope(MSG_EMERGENCY, payload, item == null ? "" : item.getUID());
    }

    private boolean isSupportedMarker(MapItem item) {
        if (item == null) return false;
        String type = item.getType();
        return type != null && (type.startsWith("a-f-G")
                || type.startsWith("a-u-G")
                || type.startsWith("a-h-G")
                || type.startsWith("a-n-G")
                || "b-m-p-s-p-i".equals(type));
    }

    private String operationFor(String eventType) {
        if (MapEvent.ITEM_REMOVED.equals(eventType)) return "delete";
        if (MapEvent.ITEM_ADDED.equals(eventType)) return OP_CREATE;
        return "update";
    }

    private boolean sendEnvelope(String type, JSONObject payload, String identity) {
        if (payload == null) return false;
        String safeIdentity = identity == null ? "" : identity;
        if (safeIdentity.isEmpty()) {
            Log.w(TAG, "Dropping downstream message with empty identity type=" + type);
            return false;
        }
        String key = type + ":" + safeIdentity;
        String payloadText = payload.toString();
        synchronized (lastSentPayloads) {
            if (payloadText.equals(lastSentPayloads.get(key))) return false;
        }
        JSONObject envelope = new JSONObject();
        put(envelope, "vers", 1);
        put(envelope, "msgType", type);
        put(envelope, "msgUid", safeIdentity.isEmpty() ? type + ":" + System.nanoTime() : safeIdentity);
        put(envelope, "t", System.currentTimeMillis());
        put(envelope, "src", "atak");
        put(envelope, "payload", payload);
        if (sender.send(envelope.toString())) {
            synchronized (lastSentPayloads) {
                lastSentPayloads.put(key, payloadText);
                trimLastSentPayloads();
            }
            return true;
        }
        return false;
    }

    private void trimLastSentPayloads() {
        while (lastSentPayloads.size() > MAX_TRACKED_MESSAGES) {
            lastSentPayloads.remove(lastSentPayloads.keySet().iterator().next());
        }
    }

    private boolean isWatchOriginatedEmergency(EmergencyAlertReceiver.EmergencyAlert alert,
                                               boolean clearAfterCheck) {
        PointMapItem item = alert == null ? null : alert.getItem();
        if (item == null) return false;
        boolean originated = WatchOriginRegistry.isEmergency(item.getUID());
        if (originated && clearAfterCheck) {
            WatchOriginRegistry.clearEmergency(item.getUID());
        }
        return originated;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isEmpty() ? first : second == null ? "" : second;
    }

    private static String alertCategory(EmergencyAlertReceiver.EmergencyAlert alert) {
        return "CAT_ATAK_EMERGENCY";
    }

    private static int alertPriority(EmergencyAlertReceiver.EmergencyAlert alert) {
        return 1;
    }

    private static void put(JSONObject object, String key, Object value) {
        if (value == null) return;
        try {
            object.put(key, value);
        } catch (Exception ignored) {
            // A malformed optional field must not stop downstream transport.
        }
    }

    private static void append(JSONArray array, JSONObject value) {
        try {
            array.put(value);
        } catch (Exception ignored) {
            // Skip only the malformed route point.
        }
    }
}
