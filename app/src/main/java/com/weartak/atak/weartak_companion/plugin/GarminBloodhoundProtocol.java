package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Converts the existing Bloodhound envelope to and from Garmin Connect IQ maps. */
final class GarminBloodhoundProtocol {
    private GarminBloodhoundProtocol() { }

    static JSONObject inbound(JSONObject envelope, String expectedRelaySession) throws JSONException {
        String type = envelope == null ? "" : envelope.optString("msgType", "");
        if (!isSupported(type)) throw new IllegalArgumentException("Unsupported Bloodhound message");
        JSONObject payload = envelope.optJSONObject("payload");
        if (payload == null) throw new IllegalArgumentException("Missing Bloodhound payload");
        validateIdentity(payload, expectedRelaySession);
        validateResult(type, payload);
        JSONObject normalized = new JSONObject();
        normalized.put("msg_type", type);
        normalized.put("payload", payload);
        return normalized;
    }

    static String outboundType(JSONObject envelope) {
        String type = envelope == null ? "" : envelope.optString("msg_type", "");
        if (!isSupported(type)) throw new IllegalArgumentException("Unsupported Bloodhound message");
        return type;
    }

    static Map<String, Object> outboundPayload(JSONObject envelope) {
        if (envelope == null || envelope.optJSONObject("payload") == null)
            throw new IllegalArgumentException("Missing Bloodhound payload");
        JSONObject payload = envelope.optJSONObject("payload");
        validateIdentity(payload, payload.optString("relaySession", null));
        validateResult(envelope.optString("msg_type"), payload);
        return toMap(payload);
    }

    private static boolean isSupported(String type) {
        return "bloodhound_control".equals(type) || "bloodhound_control_result".equals(type);
    }

    private static void validateIdentity(JSONObject payload, String expectedRelaySession) {
        Object version = payload.opt("bloodhoundVersion");
        if (!(payload.opt("relaySession") instanceof String)
                || expectedRelaySession == null
                || expectedRelaySession.trim().isEmpty()
                || expectedRelaySession.length() > 128
                || !expectedRelaySession.equals(payload.optString("relaySession"))
                || !(version instanceof Number) || ((Number) version).doubleValue() != 1.0d
                || !(payload.opt("command_id") instanceof String)
                || !(payload.opt("session_id") instanceof String)
                || !(payload.opt("target_uid") instanceof String)
                || !(payload.opt("action") instanceof String)
                || !validUuid(payload.optString("command_id", ""))
                || !validUuid(payload.optString("session_id", ""))
                || !validUid(payload.optString("target_uid", ""))
                || !("start".equals(payload.optString("action"))
                    || "stop".equals(payload.optString("action"))
                    || "update".equals(payload.optString("action"))))
            throw new IllegalArgumentException("Invalid Bloodhound command identity");
    }

    private static void validateResult(String type, JSONObject payload) {
        if (!"bloodhound_control_result".equals(type)) return;
        Object ok = payload.opt("ok");
        if (!(ok instanceof Boolean)) throw new IllegalArgumentException("Missing Bloodhound result status");
        if (Boolean.FALSE.equals(ok)) {
            Object error = payload.opt("error");
            if (!(error instanceof String) || ((String) error).trim().isEmpty()
                    || ((String) error).length() > 256)
                throw new IllegalArgumentException("Invalid Bloodhound result error");
        }
    }

    private static boolean validUuid(String value) {
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean validUid(String value) {
        if (value == null || value.isEmpty() || value.length() > 256
                || !value.equals(value.trim())) return false;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return false;
        }
        return true;
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
