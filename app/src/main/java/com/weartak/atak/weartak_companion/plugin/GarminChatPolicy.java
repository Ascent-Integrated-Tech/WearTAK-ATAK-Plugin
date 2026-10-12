package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

final class GarminChatPolicy {
    private GarminChatPolicy() {}

    static Map<String, Object> relayStatus(JSONObject hello) {
        return relayStatus(hello, true);
    }

    static Map<String, Object> relayStatus(JSONObject hello, boolean bloodhoundSupported) {
        Object session = hello.opt("relaySession");
        if (!(session instanceof String) || ((String) session).trim().isEmpty()
                || ((String) session).length() > 128) {
            throw new IllegalArgumentException("Missing Garmin relay session");
        }
        for (int i = 0; i < ((String) session).length(); i++) {
            if (Character.isISOControl(((String) session).charAt(i)))
                throw new IllegalArgumentException("Missing Garmin relay session");
        }
        Map<String, Object> status = new HashMap<>();
        status.put("relaySession", session);
        status.put("reliableDelivery", false);
        status.put("chatGroups", false);
        Object version = hello.opt("bloodhoundVersion");
        if (bloodhoundSupported && version instanceof Number
                && ((Number) version).doubleValue() == 1.0d) {
            status.put("bloodhoundVersion", 1);
            status.put("bloodhoundControl", true);
        }
        return status;
    }

    static ChatReplyPayload reply(JSONObject payload, boolean groupContact) {
        ChatReplyPayload reply = new ChatReplyPayload(payload);
        requireSupported(reply.roomUid, reply.isGroup || groupContact);
        return reply;
    }

    static Map<String, Object> received(JSONObject envelope) {
        JSONObject payload = envelope.optJSONObject("payload");
        if (!"chat_received".equals(envelope.optString("msgType")) || payload == null) {
            throw new IllegalArgumentException("Missing received chat payload");
        }
        requireSupported(payload.optString("conversationUid"), payload.optBoolean("isGroup"));
        return toMap(payload);
    }

    private static void requireSupported(String roomUid, boolean group) {
        if (group && !"All Chat Rooms".equals(roomUid)) {
            throw new IllegalArgumentException("Named Garmin group chat is not supported");
        }
    }

    private static Map<String, Object> toMap(JSONObject object) {
        Map<String, Object> values = new HashMap<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            values.put(key, toValue(object.opt(key)));
        }
        return values;
    }

    private static Object toValue(Object value) {
        if (value instanceof JSONObject) return toMap((JSONObject) value);
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            ArrayList<Object> values = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) values.add(toValue(array.opt(i)));
            return values;
        }
        return value == JSONObject.NULL ? null : value;
    }
}
