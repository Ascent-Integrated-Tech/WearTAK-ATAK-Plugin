package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Session-local forwarding policy, independent of the ATAK and Bluetooth runtimes. */
final class PhoneMarkerRelay {
    interface Writer {
        boolean write(String envelope, long session);
    }

    interface LocalRemoval {
        boolean remove();
    }

    static final class Snapshot {
        final String uid;
        final String fingerprint;
        final String xml;
        final boolean valid;
        final long time;

        Snapshot(String uid, String type, double lat, double lon, double hae, double ce,
                 double le, long time, long start, long stale, boolean excluded,
                 String fingerprint, String xml) {
            this.uid = uid;
            this.fingerprint = fingerprint;
            this.xml = xml;
            this.time = time;
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
    private final Map<String, String> pendingRevisions = new HashMap<>();
    private static final class Revision {
        final String token, fingerprint;
        final long session;
        Revision(String token, String fingerprint, long session) {
            this.token = token;
            this.fingerprint = fingerprint;
            this.session = session;
        }
    }
    private final Map<String, Revision> revisions = new HashMap<>();
    private String incomingDeletionUid;

    static boolean validToken(String token) {
        return token != null && token.matches("[a-zA-Z0-9-]{1,64}");
    }

    synchronized void watchImportStarted(String uid, long now, String revision, long readySession) {
        watchImportStarted(uid, now);
        if (validToken(revision) && readySession >= 0) {
            ensureSession(readySession);
            pendingRevisions.put(uid, revision);
        }
    }

    private void ensureSession(long readySession) {
        if (session != readySession) {
            session = readySession;
            sent.clear();
            revisions.clear();
            pendingRevisions.clear();
        }
    }

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
        String revision = pendingRevisions.remove(marker.uid);
        if (marker.valid && revision != null) {
            revisions.put(marker.uid, new Revision(revision, marker.fingerprint, session));
        }
        while (watchCopies.size() > 256) {
            watchCopies.remove(watchCopies.keySet().iterator().next());
        }
        sent.remove(marker.uid);
    }

    synchronized boolean forward(Snapshot marker, long expectedSession, long readySession,
                                 long now, Writer writer) throws JSONException {
        if (marker == null || !marker.valid || readySession < 0
                || expectedSession != readySession) return false;
        ensureSession(readySession);
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
        String token = UUID.randomUUID().toString();
        String envelope = new JSONObject().put("msg_type", "phone_marker_cot")
                .put("payload", new JSONObject().put("cot", marker.xml).put("revision", token)).toString();
        if (!writer.write(envelope, readySession)) return false;
        sent.put(marker.uid, marker.fingerprint);
        revisions.put(marker.uid, new Revision(token, marker.fingerprint, readySession));
        return true;
    }

    synchronized boolean forwardDeletion(Snapshot marker, long readySession, Writer writer)
            throws JSONException {
        if (marker == null || !marker.valid || readySession < 0) return false;
        if (marker.uid.equals(incomingDeletionUid)) return false;
        Revision revision = revisions.get(marker.uid);
        if (revision == null || revision.session != readySession
                || !revision.fingerprint.equals(marker.fingerprint)) return false;
        return writer.write(new JSONObject().put("msg_type", "marker_delete")
                .put("payload", new JSONObject().put("targetUid", marker.uid)
                        .put("revision", revision.token)).toString(), readySession);
    }

    synchronized boolean acceptsDeletion(Snapshot marker, String token, long readySession) {
        if (marker == null || !marker.valid || !validToken(token) || readySession < 0) return false;
        Revision revision = revisions.get(marker.uid);
        return revision != null && revision.session == readySession && token.equals(revision.token)
                && revision.fingerprint.equals(marker.fingerprint);
    }

    /** The only supplied operation is local map removal, never a CoT/network dispatcher. */
    synchronized boolean applyLocalDeletion(Snapshot marker, String token, long readySession,
                                            LocalRemoval removal) {
        if (!acceptsDeletion(marker, token, readySession)) return false;
        incomingDeletionUid = marker.uid;
        try {
            boolean removed = removal.remove();
            if (removed) removed(marker.uid);
            return removed;
        } finally {
            incomingDeletionUid = null;
        }
    }

    synchronized void removed(String uid) {
        sent.remove(uid);
        watchCopies.remove(uid);
        pendingImports.remove(uid);
        pendingRevisions.remove(uid);
        revisions.remove(uid);
    }

    synchronized void clear() {
        session = -1;
        sent.clear();
        watchCopies.clear();
        pendingImports.clear();
        pendingRevisions.clear();
        revisions.clear();
    }
}
