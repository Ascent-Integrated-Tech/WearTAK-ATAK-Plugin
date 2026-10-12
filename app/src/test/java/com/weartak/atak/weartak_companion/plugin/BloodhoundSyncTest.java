package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

public class BloodhoundSyncTest {
    private static class FakePort implements BloodhoundSync.Port {
        boolean connected = true;
        boolean writable = true;
        boolean failStart;
        boolean asynchronous;
        boolean failState;
        String nativeUid;
        int starts;
        int stops;
        int updates;
        final List<JSONObject> sent = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        @Override public boolean connected() { return connected; }
        @Override public boolean send(JSONObject env) {
            if (!writable) return false;
            sent.add(env);
            return true;
        }
        @Override public boolean isTracking(String uid) {
            if (failState) throw new IllegalStateException("Unsupported native SDK");
            return uid.equals(nativeUid);
        }
        @Override public void start(JSONObject target) throws Exception {
            starts++;
            if (failStart) throw new IllegalStateException("Missing target coordinates");
            if (!asynchronous) nativeUid = target.getString("target_uid");
        }
        @Override public void stop(String uid) {
            stops++;
            if (!asynchronous) nativeUid = null;
        }
        @Override public void failure(String message) { failures.add(message); }
        @Override public void update(JSONObject target) { updates++; }
    }

    private final FakePort port = new FakePort();
    private final BloodhoundSync sync = new BloodhoundSync(port);

    private JSONObject request(String action, String id, String uid) {
        try {
            return BloodhoundSync.envelope("bloodhound_control",
                    BloodhoundSync.command(action, id, uid).put("lat", 38.5).put("lon", -77.2));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private String startNative(String uid) {
        port.nativeUid = uid;
        sync.nativeStarted(BloodhoundSync.command("start", "", uid));
        return port.sent.get(port.sent.size() - 1).optJSONObject("payload")
                .optString("session_id");
    }

    private void assertResult(boolean ok) {
        JSONObject last = port.sent.get(port.sent.size() - 1);
        assertEquals("bloodhound_control_result", last.optString("msg_type"));
        assertEquals(ok, last.optJSONObject("payload").optBoolean("ok"));
    }

    @Test public void nativeStartAndEndForwardCorrelatedCommands() {
        String id = startNative("alert-1");
        UUID.fromString(id);
        port.nativeUid = null;
        sync.nativeStopped("alert-1");
        JSONObject p = port.sent.get(1).optJSONObject("payload");
        assertEquals(id, p.optString("session_id"));
        assertEquals("alert-1", p.optString("target_uid"));
        assertEquals("stop", p.optString("action"));
        assertEquals(0, port.stops);
    }

    @Test public void nativeReplacementStopsPreviousSessionBeforeStartingNext() throws Exception {
        String previous = startNative("alert-1");
        JSONObject accepted = BloodhoundSync.command("start", previous, "alert-1").put("ok", true);
        sync.receive(BloodhoundSync.envelope("bloodhound_control_result", accepted));
        port.nativeUid = "alert-2";
        sync.nativeStarted(BloodhoundSync.command("start", "", "alert-2"));

        assertEquals(3, port.sent.size());
        JSONObject stop = port.sent.get(1).optJSONObject("payload");
        assertEquals("stop", stop.optString("action"));
        assertEquals(previous, stop.optString("session_id"));
        assertEquals("alert-1", stop.optString("target_uid"));
        JSONObject start = port.sent.get(2).optJSONObject("payload");
        assertEquals("start", start.optString("action"));
        assertEquals("alert-2", start.optString("target_uid"));
    }

    @Test public void remoteStartStopConfirmWithoutEcho() {
        String id = UUID.randomUUID().toString();
        sync.receive(request("start", id, "alert-1"));
        assertResult(true);
        sync.nativeStarted(BloodhoundSync.command("start", "", "alert-1"));
        assertEquals(1, port.sent.size());
        sync.receive(request("stop", id, "alert-1"));
        assertResult(true);
        sync.nativeStopped("alert-1");
        assertEquals(2, port.sent.size());
        assertEquals(1, port.starts);
        assertEquals(1, port.stops);
    }

    @Test public void garminCommandResultEchoesCompleteWireIdentity() throws Exception {
        String id = UUID.randomUUID().toString();
        String commandId = UUID.randomUUID().toString();
        JSONObject p = BloodhoundSync.command("start", id, "alert-1")
                .put("relaySession", "phone-link")
                .put("bloodhoundVersion", 1)
                .put("command_id", commandId)
                .put("target_callsign", "Rescue")
                .put("lat", 34.0).put("lon", -117.0);
        sync.receive(BloodhoundSync.envelope("bloodhound_control", p));
        JSONObject result = port.sent.get(0).optJSONObject("payload");
        assertEquals(id, result.optString("session_id"));
        assertEquals(commandId, result.optString("command_id"));
        assertEquals("phone-link", result.optString("relaySession"));
        assertEquals(1, result.optInt("bloodhoundVersion"));
        assertEquals("alert-1", result.optString("target_uid"));
        assertEquals("start", result.optString("action"));
        assertTrue(result.optBoolean("ok"));
    }

    @Test public void concurrentGarminCommandWithDifferentIdIsRejectedWithoutReplacingPending() throws Exception {
        port.asynchronous = true;
        String sessionId = UUID.randomUUID().toString();
        String commandId = UUID.randomUUID().toString();
        JSONObject first = BloodhoundSync.command("start", sessionId, "alert-1")
                .put("relaySession", "phone-link")
                .put("bloodhoundVersion", 1).put("command_id", commandId)
                .put("lat", 38.5).put("lon", -77.2);
        sync.receive(BloodhoundSync.envelope("bloodhound_control", first));
        JSONObject replacement = new JSONObject(first.toString())
                .put("command_id", UUID.randomUUID().toString());
        sync.receive(BloodhoundSync.envelope("bloodhound_control", replacement));
        assertEquals(1, port.starts);
        assertResult(false);
        assertEquals(replacement.optString("command_id"),
                port.sent.get(port.sent.size() - 1).optJSONObject("payload").optString("command_id"));
        sync.timeout(sessionId, "start");
        assertResult(false);
    }

    @Test public void garminAcknowledgementMustMatchCommandAndTargetIdentity() throws Exception {
        String id = startNative("local");
        JSONObject sent = port.sent.get(0).optJSONObject("payload");
        sent.put("relaySession", "phone-link");
        sent.put("bloodhoundVersion", 1);
        sent.put("command_id", UUID.randomUUID().toString());

        JSONObject stale = BloodhoundSync.command("start", id, "local")
                .put("relaySession", "phone-link")
                .put("bloodhoundVersion", 1)
                .put("command_id", UUID.randomUUID().toString())
                .put("ok", false).put("error", "stale");
        sync.receive(BloodhoundSync.envelope("bloodhound_control_result", stale));
        assertTrue(port.failures.isEmpty());

        JSONObject matching = new JSONObject(sent.toString()).put("ok", true);
        sync.receive(BloodhoundSync.envelope("bloodhound_control_result", matching));
        sync.resultTimeout(id, "start");
        assertTrue(port.failures.isEmpty());
    }

    @Test public void staleSessionAndWrongTargetCannotStopCurrentTrack() {
        String old = startNative("alert-1");
        String current = startNative("alert-2");
        sync.receive(request("stop", old, "alert-1"));
        assertResult(false);
        sync.receive(request("stop", current, "alert-1"));
        assertResult(false);
        assertEquals(0, port.stops);
        assertEquals("alert-2", port.nativeUid);
        sync.receive(request("stop", current, "alert-2"));
        assertEquals(1, port.stops);
        assertResult(true);
    }

    @Test public void sameTargetRestartUsesNewSessionAndRejectsOldStop() {
        String old = startNative("alert-1");
        port.nativeUid = null;
        sync.nativeStopped("alert-1");
        String current = startNative("alert-1");
        assertNotEquals(old, current);
        sync.receive(request("stop", old, "alert-1"));
        assertEquals(0, port.stops);
    }

    @Test public void disconnectedCommandsAndNativeEdgesDoNotSyncOrReplay() {
        String old = startNative("alert-1");
        port.connected = false;
        sync.disconnected();
        sync.receive(request("stop", old, "alert-1"));
        sync.nativeStarted(BloodhoundSync.command("start", "", "alert-2"));
        assertEquals(0, port.starts);
        assertEquals(0, port.stops);
        assertEquals(1, port.sent.size());
        port.connected = true;
        assertEquals(1, port.sent.size());
        sync.receive(request("stop", old, "alert-1"));
        assertEquals(0, port.stops);
        assertResult(false);
        sync.receive(request("start", old, "alert-1"));
        assertResult(false);
    }

    @Test public void duplicateStartIsIdempotentAndEndedStartIsRejected() {
        String id = UUID.randomUUID().toString();
        sync.receive(request("start", id, "alert-1"));
        sync.receive(request("start", id, "alert-1"));
        assertEquals(1, port.starts);
        sync.receive(request("stop", id, "alert-1"));
        sync.receive(request("start", id, "alert-1"));
        assertResult(false);
        assertEquals(1, port.starts);
    }

    @Test public void remoteReplacementDoesNotEchoOldNativeEnd() throws Exception {
        String local = startNative("alert-1");
        sync.receive(BloodhoundSync.envelope("bloodhound_control_result",
                BloodhoundSync.command("start", local, "alert-1").put("ok", true)));
        String id = UUID.randomUUID().toString();
        sync.receive(request("start", id, "alert-2"));
        sync.nativeStopped("alert-1");
        sync.nativeStarted(BloodhoundSync.command("start", "", "alert-2"));
        assertEquals(2, port.sent.size());
        assertResult(true);
    }

    @Test public void changedNativeTrackBeforeStopDoesNotStopUnrelatedTarget() {
        String id = startNative("alert-1");
        port.nativeUid = "alert-2";
        sync.receive(request("stop", id, "alert-1"));
        assertEquals("alert-2", port.nativeUid);
        assertEquals(0, port.stops);
        assertResult(true);
    }

    @Test public void failuresAndTimeoutsReturnNegativeResult() {
        port.failStart = true;
        sync.receive(request("start", UUID.randomUUID().toString(), "missing"));
        assertResult(false);
        assertTrue(port.sent.get(0).optJSONObject("payload").has("error"));
        port.failStart = false;
        port.asynchronous = true;
        String id = UUID.randomUUID().toString();
        sync.receive(request("start", id, "alert-1"));
        sync.timeout(id, "start");
        assertResult(false);
        assertFalse(port.failures.isEmpty());
    }

    @Test public void invalidCommandsDoNotInvokeNativeTracking() {
        sync.receive(request("start", "not-a-uuid", "alert-1"));
        assertResult(false);
        sync.receive(request("erase", UUID.randomUUID().toString(), "alert-1"));
        assertResult(false);
        sync.receive(request("stop", UUID.randomUUID().toString(), ""));
        assertResult(false);
        assertEquals(0, port.starts);
        assertEquals(0, port.stops);
    }

    @Test public void failedTransportDoesNotRetainSharedSession() {
        port.writable = false;
        port.nativeUid = "alert-1";
        sync.nativeStarted(BloodhoundSync.command("start", "", "alert-1"));
        assertEquals(0, port.sent.size());
        assertFalse(port.failures.isEmpty());
        port.writable = true;
        sync.nativeStopped("alert-1");
        assertEquals(0, port.sent.size());
    }

    @Test public void watchNegativeResultIsVisible() throws Exception {
        String id = startNative("alert-1");
        JSONObject p = BloodhoundSync.command("start", id, "alert-1")
                .put("ok", false).put("error", "No fix");
        sync.receive(BloodhoundSync.envelope("bloodhound_control_result", p));
        assertEquals("Watch Bloodhound: No fix", port.failures.get(0));
    }

    @Test public void smallerIncomingSimultaneousStartWinsAndLosingAckCannotClearIt() throws Exception {
        String local = startNative("local");
        String remote = "00000000-0000-0000-0000-000000000001";
        sync.receive(request("start", remote, "remote"));
        assertEquals("remote", port.nativeUid);
        assertResult(true);
        JSONObject failed = BloodhoundSync.command("start", local, "local")
                .put("ok", false).put("error", "Superseded by simultaneous tracking start");
        sync.receive(BloodhoundSync.envelope("bloodhound_control_result", failed));
        assertTrue(port.failures.isEmpty());
        sync.receive(request("stop", remote, "remote"));
        assertResult(true);
        assertEquals(1, port.stops);
    }

    @Test public void largerIncomingSimultaneousStartIsRejected() {
        String local = startNative("local");
        String remote = "ffffffff-ffff-ffff-ffff-ffffffffffff";
        sync.receive(request("start", remote, "remote"));
        assertResult(false);
        assertEquals("Superseded by simultaneous tracking start",
                port.sent.get(1).optJSONObject("payload").optString("error"));
        assertEquals("local", port.nativeUid);
        sync.receive(request("stop", local, "local"));
        assertResult(true);
    }

    @Test public void ackAndTimeoutCorrelateActionAndSession() throws Exception {
        String id = startNative("local");
        JSONObject wrong = BloodhoundSync.command("stop", id, "local")
                .put("ok", false).put("error", "wrong");
        sync.receive(BloodhoundSync.envelope("bloodhound_control_result", wrong));
        assertTrue(port.failures.isEmpty());
        sync.resultTimeout(id, "stop");
        assertTrue(port.failures.isEmpty());
        sync.resultTimeout(id, "start");
        assertTrue(port.failures.get(0).contains("timed out"));
        assertEquals("local", port.nativeUid);
        sync.receive(request("stop", id, "local"));
        assertResult(false);
        assertEquals(0, port.stops);
    }

    @Test public void successfulAckCancelsTimeoutAndStopsAlsoAwaitAck() throws Exception {
        String id = startNative("local");
        JSONObject ok = BloodhoundSync.command("start", id, "local").put("ok", true);
        sync.receive(BloodhoundSync.envelope("bloodhound_control_result", ok));
        sync.resultTimeout(id, "start");
        assertTrue(port.failures.isEmpty());
        port.nativeUid = null;
        sync.nativeStopped("local");
        sync.resultTimeout(id, "stop");
        assertTrue(port.failures.get(0).contains("stop acknowledgement timed out"));
    }

    @Test public void loserTimeoutDoesNotClearRemoteWinner() {
        String local = startNative("local");
        String remote = "00000000-0000-0000-0000-000000000001";
        sync.receive(request("start", remote, "remote"));
        sync.resultTimeout(local, "start");
        assertTrue(port.failures.isEmpty());
        sync.receive(request("stop", remote, "remote"));
        assertResult(true);
        assertEquals(1, port.stops);
    }

    @Test public void stoppedSessionRetiresStartAckButKeepsStopAck() {
        String id = startNative("local");
        port.nativeUid = null;
        sync.nativeStopped("local");
        sync.resultTimeout(id, "start");
        assertTrue(port.failures.isEmpty());
        sync.resultTimeout(id, "stop");
        assertEquals(1, port.failures.size());
        assertTrue(port.failures.get(0).contains("stop acknowledgement timed out"));
    }

    @Test public void replacedSessionDoesNotReportObsoleteStartTimeout() {
        String old = startNative("first");
        startNative("second");
        sync.resultTimeout(old, "start");
        assertTrue(port.failures.isEmpty());
    }

    @Test public void updatesRequireMatchingActiveSessionAndAcknowledgeExplicitly() throws Exception {
        String id = startNative("local");
        JSONObject update = BloodhoundSync.command("update", id, "local")
                .put("lat", 34).put("lon", -117);
        sync.receive(BloodhoundSync.envelope("bloodhound_control", update));
        assertEquals(1, port.updates);
        assertEquals(2, port.sent.size());
        assertResult(true);
        sync.receive(request("update", UUID.randomUUID().toString(), "local"));
        sync.receive(request("update", id, "other"));
        assertEquals(1, port.updates);
        assertEquals(4, port.sent.size());
        assertResult(false);
        port.nativeUid = null;
        sync.receive(BloodhoundSync.envelope("bloodhound_control", update));
        assertEquals(1, port.updates);
        assertEquals(0, port.starts);
        assertEquals(0, port.stops);
        assertEquals(5, port.sent.size());
        assertResult(false);
    }

    @Test public void newSessionOnAlreadyTrackedTargetRefreshesSnapshotButDuplicateDoesNot() {
        String local = startNative("local");
        // A confirmed old session is not a simultaneous start.
        try {
            sync.receive(BloodhoundSync.envelope("bloodhound_control_result",
                    BloodhoundSync.command("start", local, "local").put("ok", true)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        String remote = UUID.randomUUID().toString();
        sync.receive(request("start", remote, "local"));
        assertEquals(1, port.updates);
        assertEquals(0, port.starts);
        assertResult(true);
        sync.receive(request("start", remote, "local"));
        assertEquals(1, port.updates);
        assertEquals(0, port.starts);
        assertResult(true);
    }

    @Test public void nativeUpdatesSendSnapshotWithoutCreatingAckDeadline() throws Exception {
        String id = startNative("local");
        JSONObject target = new JSONObject().put("target_uid", "local")
                .put("lat", 34).put("lon", -117).put("target_callsign", "Rescue");
        sync.nativeUpdated(target);
        JSONObject p = port.sent.get(1).optJSONObject("payload");
        assertEquals("update", p.optString("action"));
        assertEquals(id, p.optString("session_id"));
        assertEquals(34, p.optDouble("lat"), 0);
        sync.resultTimeout(id, "update");
        assertTrue(port.failures.isEmpty());
        sync.nativeUpdated(new JSONObject().put("target_uid", "other"));
        assertEquals(2, port.sent.size());
        sync.disconnected();
        sync.nativeUpdated(target);
        assertEquals(2, port.sent.size());
    }

    @Test public void unavailableNativeApiReportsFailureRatherThanSuccessOrCrash() {
        String id = startNative("alert-1");
        port.failState = true;
        sync.receive(request("stop", id, "alert-1"));
        assertResult(false);
        assertEquals(0, port.stops);
        assertEquals("alert-1", port.nativeUid);
        sync.receive(request("start", UUID.randomUUID().toString(), "alert-2"));
        assertResult(false);
        assertEquals(0, port.starts);
    }
}
