package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Main-thread session coordinator, independent of the ATAK/Android runtime. */
final class BloodhoundSync {
    interface Port {
        boolean connected();
        boolean send(JSONObject envelope);
        boolean isTracking(String uid);
        void start(JSONObject target) throws Exception;
        void stop(String uid) throws Exception;
        void failure(String message);
        default void update(JSONObject target) throws Exception { }
        default void awaitResult(String id, String action) { }
    }

    private final Port port;
    private final LinkedHashSet<String> retired = new LinkedHashSet<>();
    private final LinkedHashMap<String, JSONObject> outgoing = new LinkedHashMap<>();
    private String sessionId;
    private String targetUid;
    private JSONObject pending;

    BloodhoundSync(Port port) {
        this.port = port;
    }

    void disconnected() {
        outgoing.clear();
        clearSession();
    }

    private void clearSession() {
        retire(sessionId);
        if (pending != null) retire(pending.optString("session_id", null));
        sessionId = null;
        targetUid = null;
        pending = null;
    }

    void receive(JSONObject envelope) {
        JSONObject p = envelope.optJSONObject("payload");
        if (p == null || !port.connected()) return;
        if ("bloodhound_control_result".equals(envelope.optString("msg_type"))) {
            receivedResult(p);
            return;
        }
        String id = p.optString("session_id", "");
        String action = p.optString("action", "");
        String uid = p.optString("target_uid", "");
        if ("update".equals(action)) {
            if (pending == null && id.equals(sessionId) && uid.equals(targetUid)) {
                try {
                    if (port.isTracking(uid)) port.update(p);
                } catch (Exception e) {
                    port.failure("Bloodhound target update failed: " + e.getMessage());
                }
            }
            return;
        }
        if (!validUuid(id) || uid.trim().isEmpty()
                || (!"start".equals(action) && !"stop".equals(action))) {
            result(p, false, "Invalid Bloodhound command");
            return;
        }
        if (pending != null) {
            if (!id.equals(pending.optString("session_id"))
                    || !action.equals(pending.optString("action"))
                    || !uid.equals(pending.optString("target_uid"))) {
                result(p, false, "Bloodhound command in progress");
            }
            return;
        }
        if ("stop".equals(action)) {
            if (!id.equals(sessionId) || !uid.equals(targetUid)) {
                result(p, false, "Stale or unrelated Bloodhound session");
                return;
            }
            pending = p;
            try {
                if (!port.isTracking(uid)) {
                    finishStop();
                    return;
                }
                port.stop(uid);
                if (pending != null && !port.isTracking(uid)) finishStop();
            } catch (Exception e) {
                failPending(e.getMessage());
            }
        } else {
            if (retired.contains(id)) {
                result(p, false, "Bloodhound session already ended");
                return;
            }
            if (id.equals(sessionId) && !uid.equals(targetUid)) {
                result(p, false, "Session target mismatch");
                return;
            }
            if (sessionId != null && !id.equals(sessionId)
                    && outgoing.containsKey(key(sessionId, "start"))) {
                if (sessionId.compareTo(id) < 0) {
                    retire(id);
                    result(p, false, "Superseded by simultaneous tracking start");
                    return;
                }
                // Retire the losing start's ACK deadline with its session.
                retire(sessionId);
            }
            pending = p;
            try {
                if (port.isTracking(uid)) {
                    if (!id.equals(sessionId)) port.update(p);
                    finishStart();
                    return;
                }
                port.start(p);
                if (pending != null && port.isTracking(uid)) finishStart();
            } catch (Exception e) {
                failPending(e.getMessage());
            }
        }
    }

    void nativeStarted(JSONObject target) {
        String uid = target.optString("target_uid");
        if (pending != null && "start".equals(pending.optString("action"))
                && uid.equals(pending.optString("target_uid"))) {
            finishStart();
            return;
        }
        if (pending != null) failPending("Native tracking changed");
        if (!port.connected()) {
            disconnected();
            return;
        }
        if (uid.equals(targetUid)) return;
        if (sessionId != null) nativeStopped(targetUid);
        sessionId = UUID.randomUUID().toString();
        targetUid = uid;
        try {
            JSONObject p = new JSONObject(target.toString());
            p.put("action", "start");
            p.put("session_id", sessionId);
            if (!sendCommand(p)) {
                port.failure("Unable to send Bloodhound start");
                disconnected();
            }
        } catch (JSONException e) {
            port.failure(e.getMessage());
            disconnected();
        }
    }

    void nativeStopped(String uid) {
        if (pending != null) {
            if ("stop".equals(pending.optString("action"))
                    && uid.equals(pending.optString("target_uid"))) finishStop();
            // A remote replacement start ends the previous native link first.
            return;
        }
        if (!uid.equals(targetUid)) return;
        if (port.connected()) {
            JSONObject p = command("stop", sessionId, uid);
            if (!sendCommand(p)) {
                port.failure("Unable to send Bloodhound stop");
            }
        }
        clearSession();
    }

    void nativeUpdated(JSONObject target) {
        if (pending != null || sessionId == null || !port.connected()
                || !target.optString("target_uid").equals(targetUid)) return;
        try {
            JSONObject p = new JSONObject(target.toString());
            p.put("action", "update");
            p.put("session_id", sessionId);
            if (!port.send(envelope("bloodhound_control", p)))
                port.failure("Unable to send Bloodhound target update");
        } catch (JSONException e) {
            port.failure(e.getMessage());
        }
    }

    void timeout(String id, String action) {
        if (pending == null || !id.equals(pending.optString("session_id"))
                || !action.equals(pending.optString("action"))) return;
        String uid = pending.optString("target_uid");
        try {
            if ("start".equals(action) && port.isTracking(uid)) finishStart();
            else if ("stop".equals(action) && !port.isTracking(uid)) finishStop();
            else failPending("ATAK did not confirm Bloodhound " + action);
        } catch (Exception e) {
            failPending(e.getMessage());
        }
    }

    private void finishStart() {
        JSONObject p = pending;
        if (!p.optString("session_id").equals(sessionId)) retire(sessionId);
        sessionId = p.optString("session_id");
        targetUid = p.optString("target_uid");
        pending = null;
        result(p, true, null);
    }

    private void finishStop() {
        JSONObject p = pending;
        pending = null;
        clearSession();
        result(p, true, null);
    }

    private void failPending(String error) {
        JSONObject p = pending;
        pending = null;
        if ("start".equals(p.optString("action"))) retire(p.optString("session_id"));
        result(p, false, error == null ? "Native Bloodhound failed" : error);
        try {
            if (targetUid != null && !port.isTracking(targetUid)) disconnected();
        } catch (Exception e) {
            disconnected();
        }
    }

    private void result(JSONObject request, boolean ok, String error) {
        try {
            JSONObject p = new JSONObject();
            p.put("session_id", request.optString("session_id"));
            p.put("action", request.optString("action"));
            p.put("ok", ok);
            if (error != null) p.put("error", error);
            if (port.connected() && !port.send(envelope("bloodhound_control_result", p))) {
                port.failure("Unable to send Bloodhound result");
            }
            if (!ok) port.failure(error);
        } catch (JSONException e) {
            port.failure(e.getMessage());
        }
    }

    private void retire(String id) {
        if (id == null) return;
        outgoing.remove(key(id, "start"));
        retired.add(id);
        if (retired.size() > 128) retired.remove(retired.iterator().next());
    }

    private boolean sendCommand(JSONObject p) {
        if (!port.send(envelope("bloodhound_control", p))) return false;
        String id = p.optString("session_id");
        String action = p.optString("action");
        if (outgoing.size() >= 128) {
            JSONObject oldest = outgoing.remove(outgoing.keySet().iterator().next());
            failedResult(oldest, "Watch Bloodhound acknowledgement queue overflow");
        }
        outgoing.put(key(id, action), p);
        port.awaitResult(id, action);
        return true;
    }

    private void receivedResult(JSONObject p) {
        Object ok = p.opt("ok");
        if (!(ok instanceof Boolean)) return;
        JSONObject sent = outgoing.remove(key(p.optString("session_id"), p.optString("action")));
        if (sent == null) return;
        if (!((Boolean) ok)) {
            failedResult(sent, "Watch Bloodhound: " + p.optString("error", "command failed"));
        }
    }

    void resultTimeout(String id, String action) {
        JSONObject sent = outgoing.remove(key(id, action));
        if (sent != null) failedResult(sent, "Watch Bloodhound " + action + " acknowledgement timed out");
    }

    private void failedResult(JSONObject sent, String message) {
        port.failure(message);
        if ("start".equals(sent.optString("action"))
                && sent.optString("session_id").equals(sessionId)) {
            clearSession();
        }
    }

    private static String key(String id, String action) {
        return action + ":" + id;
    }

    static JSONObject command(String action, String id, String uid) {
        JSONObject p = new JSONObject();
        try {
            p.put("action", action);
            p.put("session_id", id);
            p.put("target_uid", uid);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
        return p;
    }

    static JSONObject envelope(String type, JSONObject payload) {
        JSONObject env = new JSONObject();
        try {
            env.put("msg_type", type);
            env.put("payload", payload);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
        return env;
    }

    private static boolean validUuid(String id) {
        try {
            return UUID.fromString(id).toString().equalsIgnoreCase(id);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
