package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Session-local forwarding policy, independent of the ATAK and Bluetooth runtimes. */
final class PhoneMarkerRelay {
    interface Writer {
        boolean write(String envelope, long session);
    }

    static final class Snapshot {
        final String uid;
        final String fingerprint;
        final String xml;
        final boolean valid;

        Snapshot(String uid, String type, double lat, double lon, double hae, double ce,
                 double le, long time, long start, long stale, boolean excluded,
                 String fingerprint, String xml) {
            this.uid = uid;
            this.fingerprint = fingerprint;
            this.xml = xml;
            valid = uid != null && !uid.trim().isEmpty() && supportedType(type)
                    && !excluded && Double.isFinite(lat) && Math.abs(lat) <= 90
                    && Double.isFinite(lon) && Math.abs(lon) <= 180
                    && Double.isFinite(hae) && Double.isFinite(ce) && Double.isFinite(le)
                    && time > 0 && start > 0 && stale > 0 && xml != null
                    && fingerprint != null;
        }
    }

    private long session = -1;
    private final Map<String, String> sent = new HashMap<>();
    private final Map<String, String> watchCopies = new LinkedHashMap<>();
    private final Map<String, Long> pendingImports = new LinkedHashMap<>();

    static boolean supportedType(String type) {
        return "a-f-G".equals(type) || "a-u-G".equals(type) || "a-h-G".equals(type)
                || "a-n-G".equals(type) || "b-m-p-s-p-i".equals(type);
    }

    synchronized void watchImportStarted(String uid, long now) {
        if (uid != null && !uid.trim().isEmpty()) {
            pendingImports.put(uid, now + 5000);
            while (pendingImports.size() > 256) {
                pendingImports.remove(pendingImports.keySet().iterator().next());
            }
        }
    }

    synchronized void watchImported(Snapshot marker) {
        if (marker == null) return;
        pendingImports.remove(marker.uid);
        watchCopies.put(marker.uid, marker.fingerprint);
        while (watchCopies.size() > 256) {
            watchCopies.remove(watchCopies.keySet().iterator().next());
        }
        sent.remove(marker.uid);
    }

    synchronized boolean forward(Snapshot marker, long expectedSession, long readySession,
                                 long now, Writer writer) throws JSONException {
        if (marker == null || !marker.valid || readySession < 0
                || expectedSession != readySession) return false;
        if (session != readySession) {
            session = readySession;
            sent.clear();
        }
        Long pendingUntil = pendingImports.get(marker.uid);
        if (pendingUntil != null) {
            if (now < pendingUntil) return false;
            pendingImports.remove(marker.uid);
        }
        String watchCopy = watchCopies.get(marker.uid);
        if (marker.fingerprint.equals(watchCopy)) return false;
        // A real phone edit relinquishes the echo suppression, not the UID itself.
        watchCopies.remove(marker.uid);
        if (marker.fingerprint.equals(sent.get(marker.uid))) return false;
        String envelope = new JSONObject().put("msg_type", "phone_marker_cot")
                .put("payload", new JSONObject().put("cot", marker.xml)).toString();
        if (!writer.write(envelope, readySession)) return false;
        sent.put(marker.uid, marker.fingerprint);
        return true;
    }

    synchronized void removed(String uid) {
        sent.remove(uid);
        watchCopies.remove(uid);
        pendingImports.remove(uid);
    }

    synchronized void clear() {
        session = -1;
        sent.clear();
        watchCopies.clear();
        pendingImports.clear();
    }
}
