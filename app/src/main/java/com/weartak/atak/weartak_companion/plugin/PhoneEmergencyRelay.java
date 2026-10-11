package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;

final class PhoneEmergencyRelay {
    interface Sender {
        boolean send(String json);
    }

    private boolean active;
    private final Set<String> sentEvents = new LinkedHashSet<>();

    synchronized void start() {
        if (!active) sentEvents.clear();
        active = true;
    }

    synchronized void stop() {
        active = false;
        sentEvents.clear();
    }

    static boolean isEmergencyType(String type) {
        return type != null && (type.equals("b-a-g") || type.equals("b-a-o")
                || type.startsWith("b-a-o-"));
    }

    synchronized boolean forward(String type, String xml, boolean connected,
                                 boolean watchOrigin, Sender sender) throws JSONException {
        if (!active || !connected || watchOrigin || !isEmergencyType(type)
                || xml == null || xml.isEmpty() || sentEvents.contains(xml)) {
            return true;
        }
        JSONObject envelope = new JSONObject()
                .put("msg_type", "phone_emergency_cot")
                .put("payload", new JSONObject().put("cot", xml));
        if (!sender.send(envelope.toString())) return false;
        // The same event can arrive through both ATAK's importer broadcast and fallback listener.
        sentEvents.add(xml);
        while (sentEvents.size() > 256) {
            sentEvents.remove(sentEvents.iterator().next());
        }
        return true;
    }
}
