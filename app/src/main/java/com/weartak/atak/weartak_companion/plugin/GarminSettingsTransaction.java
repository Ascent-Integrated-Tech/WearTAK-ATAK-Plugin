package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;

import java.util.Map;

/** Correlates one Garmin settings request/apply with its watch response snapshot. */
final class GarminSettingsTransaction {
    static final long TIMEOUT_MILLIS = 15_000L;

    enum Result { IGNORED, WAITING, SUCCEEDED, FAILED, TIMED_OUT }
    private enum Kind { REQUEST, APPLY }

    private static final class Pending {
        final Kind kind;
        final String transactionId;
        final Object link;
        final long generation;
        final long startedAt;
        final Map<String, Object> requested;
        boolean acknowledged;
        boolean snapshotReceived;
        JSONObject snapshot;

        Pending(Kind kind, String transactionId, Object link, long generation,
                long startedAt, Map<String, Object> requested) {
            this.kind = kind;
            this.transactionId = transactionId;
            this.link = link;
            this.generation = generation;
            this.startedAt = startedAt;
            this.requested = requested;
        }
    }

    private long generation;
    private Pending pending;
    private JSONObject confirmedSnapshot;

    long beginRequest(String transactionId, Object link, long now) {
        return begin(Kind.REQUEST, transactionId, link, now, null);
    }

    long beginApply(String transactionId, Object link, long now,
            Map<String, Object> requested) {
        if (requested == null) throw new IllegalArgumentException("Expected settings are required.");
        return begin(Kind.APPLY, transactionId, link, now, requested);
    }

    private long begin(Kind kind, String transactionId, Object link, long now,
            Map<String, Object> requested) {
        if (!GarminSettingsProtocol.isValidTransactionId(transactionId)) {
            throw new IllegalArgumentException("Invalid settings transaction ID.");
        }
        if (link == null) throw new IllegalArgumentException("A current Garmin watch link is required.");
        generation++;
        confirmedSnapshot = null;
        pending = new Pending(kind, transactionId, link, generation, now, requested);
        return generation;
    }

    Result acknowledge(String transactionId, Object link, long attempt, long now, boolean ok) {
        Result expiration = expire(transactionId, link, attempt, now);
        if (expiration != Result.IGNORED) return expiration;
        if (!matches(transactionId, link, attempt) || pending.kind != Kind.APPLY) return Result.IGNORED;
        if (!ok) {
            clearPending();
            return Result.FAILED;
        }
        pending.acknowledged = true;
        return completeIfReady();
    }

    Result receiveSnapshot(String transactionId, Object link, long attempt, long now,
            JSONObject snapshot) {
        Result expiration = expire(transactionId, link, attempt, now);
        if (expiration != Result.IGNORED) return expiration;
        if (!matches(transactionId, link, attempt)) return Result.IGNORED;
        if (!GarminSettingsProtocol.isCompleteSnapshot(snapshot)) {
            clearPending();
            return Result.FAILED;
        }
        if (pending.kind == Kind.APPLY
                && !GarminSettingsProtocol.matchesRequestedProfile(pending.requested, snapshot)) {
            clearPending();
            return Result.FAILED;
        }
        pending.snapshotReceived = true;
        pending.snapshot = snapshot;
        if (pending.kind == Kind.REQUEST) {
            confirmedSnapshot = snapshot;
            clearPending();
            return Result.SUCCEEDED;
        }
        return completeIfReady();
    }

    Result timeout(String transactionId, Object link, long attempt, long now) {
        return expire(transactionId, link, attempt, now);
    }

    void cancel() {
        generation++;
        pending = null;
        confirmedSnapshot = null;
    }

    boolean isPending() { return pending != null; }
    String transactionId() { return pending == null ? null : pending.transactionId; }
    long generation() { return generation; }
    JSONObject takeConfirmedSnapshot() {
        JSONObject snapshot = confirmedSnapshot;
        confirmedSnapshot = null;
        return snapshot;
    }

    private Result expire(String transactionId, Object link, long attempt, long now) {
        if (!matches(transactionId, link, attempt)) return Result.IGNORED;
        if (now - pending.startedAt < TIMEOUT_MILLIS) return Result.IGNORED;
        clearPending();
        return Result.TIMED_OUT;
    }

    private boolean matches(String transactionId, Object link, long attempt) {
        return pending != null && pending.generation == attempt
                && pending.link == link && pending.transactionId.equals(transactionId);
    }

    private Result completeIfReady() {
        if (pending.acknowledged && pending.snapshotReceived) {
            confirmedSnapshot = pending.snapshot;
            clearPending();
            return Result.SUCCEEDED;
        }
        return Result.WAITING;
    }

    private void clearPending() {
        pending = null;
        generation++;
    }
}
