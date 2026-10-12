package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

public class GarminSettingsTransactionTest {
    private static final String FIRST = "12345678-1234-1234-1234-123456789abc";
    private static final String SECOND = "abcdefab-cdef-abcd-efab-cdefabcdefab";

    private JSONObject snapshot(String transactionId) throws Exception {
        return new JSONObject()
                .put("transactionId", transactionId)
                .put("callsign", "WATCH")
                .put("team", "Blue")
                .put("role", "Medic")
                .put("reportIntSecs", 30)
                .put("dynamicReporting", false)
                .put("phoneManagedSettings", true);
    }

    @Test public void setConfirmationAcceptsAckAndSnapshotInEitherOrder() throws Exception {
        GarminSettingsTransaction tracker = new GarminSettingsTransaction();
        Object link = new Object();
        Map<String, Object> requested = GarminSettingsProtocol.phoneSettings(
                FIRST, "WATCH", 30, "Blue", "Medic", true);
        long first = tracker.beginApply(FIRST, link, 100, requested);
        assertEquals(GarminSettingsTransaction.Result.WAITING,
                tracker.receiveSnapshot(FIRST, link, first, 101, snapshot(FIRST)));
        assertEquals(GarminSettingsTransaction.Result.SUCCEEDED,
                tracker.acknowledge(FIRST, link, first, 102, true));
        assertEquals("WATCH", tracker.takeConfirmedSnapshot().optString("callsign"));

        long second = tracker.beginApply(SECOND, link, 200, GarminSettingsProtocol.phoneSettings(
                SECOND, "WATCH", 30, "Blue", "Medic", true));
        assertEquals(GarminSettingsTransaction.Result.WAITING,
                tracker.acknowledge(SECOND, link, second, 201, true));
        assertEquals(GarminSettingsTransaction.Result.SUCCEEDED,
                tracker.receiveSnapshot(SECOND, link, second, 202, snapshot(SECOND)));
        assertEquals("WATCH", tracker.takeConfirmedSnapshot().optString("callsign"));
        assertFalse(tracker.isPending());
    }

    @Test public void negativeAckAndMismatchedSnapshotCannotConfirmSettings() throws Exception {
        GarminSettingsTransaction tracker = new GarminSettingsTransaction();
        Object link = new Object();
        long attempt = tracker.beginApply(FIRST, link, 100,
                GarminSettingsProtocol.phoneSettings(FIRST, "WATCH", 30, "Blue", "Medic", true));
        assertEquals(GarminSettingsTransaction.Result.FAILED,
                tracker.acknowledge(FIRST, link, attempt, 101, false));
        assertEquals(GarminSettingsTransaction.Result.IGNORED,
                tracker.receiveSnapshot(FIRST, link, attempt, 102, snapshot(FIRST)));

        long retry = tracker.beginApply(SECOND, link, 200,
                GarminSettingsProtocol.phoneSettings(SECOND, "WATCH", 30, "Blue", "Medic", true));
        JSONObject changed = snapshot(SECOND).put("role", "Team Member");
        assertEquals(GarminSettingsTransaction.Result.FAILED,
                tracker.receiveSnapshot(SECOND, link, retry, 201, changed));
        assertEquals(GarminSettingsTransaction.Result.IGNORED,
                tracker.acknowledge(SECOND, link, retry, 202, true));
    }

    @Test public void staleUncorrelatedOrOtherDeviceMessagesCannotFinishCurrentRequest() throws Exception {
        GarminSettingsTransaction tracker = new GarminSettingsTransaction();
        Object currentLink = new Object();
        Object otherLink = new Object();
        long attempt = tracker.beginRequest(FIRST, currentLink, 100);
        assertEquals(GarminSettingsTransaction.Result.IGNORED,
                tracker.acknowledge(null, currentLink, attempt, 101, true));
        assertEquals(GarminSettingsTransaction.Result.IGNORED,
                tracker.receiveSnapshot(null, currentLink, attempt, 101, snapshot(FIRST)));
        assertEquals(GarminSettingsTransaction.Result.IGNORED,
                tracker.receiveSnapshot(FIRST, otherLink, attempt, 102, snapshot(FIRST)));
        assertEquals(GarminSettingsTransaction.Result.IGNORED,
                tracker.receiveSnapshot(SECOND, currentLink, attempt, 103, snapshot(SECOND)));
        assertTrue(tracker.isPending());
        assertEquals(GarminSettingsTransaction.Result.SUCCEEDED,
                tracker.receiveSnapshot(FIRST, currentLink, attempt, 104, snapshot(FIRST)));
    }

    @Test public void timeoutAndReplacementFenceOldResponses() throws Exception {
        GarminSettingsTransaction tracker = new GarminSettingsTransaction();
        Object link = new Object();
        long oldAttempt = tracker.beginRequest(FIRST, link, 100);
        assertEquals(GarminSettingsTransaction.Result.IGNORED,
                tracker.timeout(FIRST, link, oldAttempt, 100 + GarminSettingsTransaction.TIMEOUT_MILLIS - 1));
        assertEquals(GarminSettingsTransaction.Result.TIMED_OUT,
                tracker.timeout(FIRST, link, oldAttempt, 100 + GarminSettingsTransaction.TIMEOUT_MILLIS));

        long replacedAttempt = tracker.beginRequest(FIRST, link, 20_000);
        long currentAttempt = tracker.beginRequest(SECOND, link, 20_001);
        assertEquals(GarminSettingsTransaction.Result.IGNORED,
                tracker.receiveSnapshot(FIRST, link, replacedAttempt, 20_002, snapshot(FIRST)));
        assertEquals(GarminSettingsTransaction.Result.SUCCEEDED,
                tracker.receiveSnapshot(SECOND, link, currentAttempt, 20_003, snapshot(SECOND)));

        long disconnectedAttempt = tracker.beginRequest(FIRST, link, 30_000);
        tracker.cancel();
        assertEquals(GarminSettingsTransaction.Result.IGNORED,
                tracker.receiveSnapshot(FIRST, link, disconnectedAttempt, 30_001, snapshot(FIRST)));
    }

    @Test public void requestNeedsOnlyCorrelatedCompleteSnapshotAndApplyRequiresExactProjection()
            throws Exception {
        GarminSettingsTransaction tracker = new GarminSettingsTransaction();
        Object link = new Object();
        long request = tracker.beginRequest(FIRST, link, 1);
        assertEquals(GarminSettingsTransaction.Result.FAILED,
                tracker.receiveSnapshot(FIRST, link, request, 2,
                        new JSONObject().put("transactionId", FIRST).put("callsign", "WATCH")));

        Map<String, Object> requested = GarminSettingsProtocol.phoneSettings(
                SECOND, "WATCH", 30, "Blue", "Medic", true);
        long apply = tracker.beginApply(SECOND, link, 3, requested);
        JSONObject mismatchedInterval = snapshot(SECOND).put("reportIntSecs", 31);
        assertEquals(GarminSettingsTransaction.Result.FAILED,
                tracker.receiveSnapshot(SECOND, link, apply, 4, mismatchedInterval));
    }
}
