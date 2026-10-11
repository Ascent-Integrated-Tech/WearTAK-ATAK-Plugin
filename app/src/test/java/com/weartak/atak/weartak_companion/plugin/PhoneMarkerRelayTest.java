package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class PhoneMarkerRelayTest {
    @Test public void incomingDeletionUsesOnlyLocalRemovalAndCannotDispatchEchoOrNetworkDelete() throws Exception {
        PhoneMarkerRelay relay = new PhoneMarkerRelay();
        relay.watchImportStarted("phone", 0, "shared-revision", 7);
        relay.watchImported(marker("watch"));
        List<String> operations = new ArrayList<>();
        assertTrue(relay.applyLocalDeletion(marker("watch"), "shared-revision", 7, () -> {
            operations.add("local removeFromGroup");
            try {
                // ATAK emits ITEM_REMOVED synchronously during local map removal.
                assertFalse(relay.forwardDeletion(marker("watch"), 7, (json, session) -> {
                    operations.add("outbound BLE deletion");
                    return true;
                }));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            relay.removed("phone");
            return true;
        }));
        assertEquals(java.util.Collections.singletonList("local removeFromGroup"), operations);
        assertFalse(relay.applyLocalDeletion(marker("watch"), "shared-revision", 7, () -> {
            fail("Duplicate must not invoke local removal");
            return true;
        }));
    }

    @Test public void changedOrEndedSessionNeverInvokesLocalDeletionAndRemovalFailureDoesNotEcho() {
        PhoneMarkerRelay relay = new PhoneMarkerRelay();
        relay.watchImportStarted("phone", 0, "shared-revision", 7);
        relay.watchImported(marker("watch"));
        PhoneMarkerRelay.LocalRemoval failIfCalled = () -> {
            fail("Unrelated/newer points and old sessions must survive");
            return true;
        };
        assertFalse(relay.applyLocalDeletion(marker("phone edit"), "shared-revision", 7, failIfCalled));
        assertFalse(relay.applyLocalDeletion(marker("watch"), "shared-revision", 8, failIfCalled));
        assertFalse(relay.applyLocalDeletion(marker("watch"), "shared-revision", 7, () -> false));
        assertTrue(relay.acceptsDeletion(marker("watch"), "shared-revision", 7));
    }
    @Test public void phoneDeletionRequiresSharedUnchangedRevisionAndCurrentSession() throws Exception {
        PhoneMarkerRelay relay = new PhoneMarkerRelay();
        List<String> writes = new ArrayList<>();
        assertFalse(relay.forwardDeletion(marker("initial"), 7, (json, session) -> writes.add(json)));
        relay.forward(marker("initial"), 7, 7, 0, (json, session) -> writes.add(json));
        String token = new JSONObject(writes.get(0)).getJSONObject("payload").getString("revision");
        assertTrue(relay.acceptsDeletion(marker("initial"), token, 7));
        assertFalse(relay.acceptsDeletion(marker("edited before export"), token, 7));
        assertFalse(relay.acceptsDeletion(marker("initial"), token, 8));
        assertFalse(relay.forwardDeletion(marker("edited before export"), 7, (json, session) -> true));
        assertTrue(relay.forwardDeletion(marker("initial"), 7, (json, session) -> writes.add(json)));
        JSONObject deletion = new JSONObject(writes.get(1));
        assertEquals("marker_delete", deletion.getString("msg_type"));
        assertEquals("phone", deletion.getJSONObject("payload").getString("targetUid"));
        assertEquals(token, deletion.getJSONObject("payload").getString("revision"));
        relay.removed("phone");
        assertFalse(relay.acceptsDeletion(marker("initial"), token, 7));
        assertFalse(relay.forwardDeletion(marker("initial"), 7, (json, session) -> true));
    }

    @Test public void watchImportDeletionAndPhoneEditSupersedeOldTokenWithoutEcho() throws Exception {
        PhoneMarkerRelay relay = new PhoneMarkerRelay();
        relay.watchImportStarted("phone", 0, "watch-revision", 7);
        relay.watchImported(marker("watch"));
        assertTrue(relay.acceptsDeletion(marker("watch"), "watch-revision", 7));
        assertFalse(relay.forward(marker("watch"), 7, 7, 1, (json, session) -> true));
        assertTrue(relay.forwardDeletion(marker("watch"), 7, (json, session) -> true));
        assertTrue(relay.forward(marker("edited"), 7, 7, 2, (json, session) -> true));
        assertFalse(relay.acceptsDeletion(marker("edited"), "watch-revision", 7));
        assertFalse(relay.acceptsDeletion(marker("watch"), "watch-revision", 7));
        relay.clear();
        assertFalse(relay.acceptsDeletion(marker("edited"), "watch-revision", 7));
    }

    @Test public void unsupportedUnsharedMalformedAndRejectedDeletesAreInert() throws Exception {
        PhoneMarkerRelay relay = new PhoneMarkerRelay();
        relay.watchImportStarted("phone", 0, "watch-revision", 7);
        relay.watchImported(marker("watch"));
        assertFalse(relay.acceptsDeletion(marker("watch"), "", 7));
        assertFalse(relay.acceptsDeletion(marker("watch"), "bad\nrevision", 7));
        assertFalse(relay.forwardDeletion(marker("watch"), -1, (json, session) -> true));
        assertFalse(relay.forwardDeletion(marker("watch"), 7, (json, session) -> false));
        assertTrue(relay.acceptsDeletion(marker("watch"), "watch-revision", 7));
        assertFalse(relay.acceptsDeletion(
                snapshot("phone", "b-a", 38, -77, 0, 1, 1, 1, 1, 2, false, "watch"),
                "watch-revision", 7));
    }
    private static final String XML = "<event uid='phone' type='a-f-G' time='2026-10-10T12:00:00Z' "
            + "start='2026-10-10T12:00:00Z' stale='2026-10-10T12:02:00Z'>"
            + "<point lat='38' lon='-77' hae='0' ce='9999999' le='9999999'/>"
            + "<detail><contact callsign='Original'/><remarks>unchanged</remarks></detail></event>";

    private PhoneMarkerRelay.Snapshot marker(String fingerprint) {
        return snapshot("phone", "a-f-G", 38, -77, 0, 9999999, 9999999,
                1, 1, 2, false, fingerprint);
    }

    private PhoneMarkerRelay.Snapshot snapshot(String uid, String type, double lat, double lon,
            double hae, double ce, double le, long time, long start, long stale,
            boolean excluded, String fingerprint) {
        return new PhoneMarkerRelay.Snapshot(uid, type, lat, lon, hae, ce, le, time, start,
                stale, excluded, fingerprint, XML);
    }

    @Test public void createAndUpdatesUseExactEnvelopeAndPreserveExport() throws Exception {
        PhoneMarkerRelay relay = new PhoneMarkerRelay();
        List<String> writes = new ArrayList<>();
        PhoneMarkerRelay.Writer writer = (json, session) -> writes.add(json);
        assertTrue(relay.forward(marker("initial"), 7, 7, 0, writer));
        assertFalse(relay.forward(marker("initial"), 7, 7, 0, writer));
        assertTrue(relay.forward(marker("moved"), 7, 7, 0, writer));
        assertTrue(relay.forward(marker("retitled"), 7, 7, 0, writer));
        assertEquals(3, writes.size());
        JSONObject envelope = new JSONObject(writes.get(0));
        assertEquals(2, envelope.length());
        assertEquals("phone_marker_cot", envelope.getString("msg_type"));
        assertEquals(XML, envelope.getJSONObject("payload").getString("cot"));
    }

    @Test public void onlyWatchSupportedPointTypesPass() {
        for (String type : new String[]{"a-f-G", "a-u-G", "a-h-G", "a-n-G", "b-m-p-s-p-i"}) {
            assertTrue(PhoneMarkerRelay.supportedType(type));
        }
        for (String type : new String[]{"a-f-G-U-C", "a-f-G-E-V", "b-a", "u-d-f", "b-m-r",
                "b-t-f", "t-x-d-d", "", "a-f-G-extra"}) {
            assertFalse(PhoneMarkerRelay.supportedType(type));
        }
        assertFalse(PhoneMarkerRelay.supportedType(null));
    }

    @Test public void identityPointTimestampsAndPliEmergencyExclusionsAreValidated() {
        assertFalse(snapshot(" ", "a-f-G", 0, 0, 0, 0, 0, 1, 1, 2, false, "x").valid);
        assertFalse(snapshot(null, "a-f-G", 0, 0, 0, 0, 0, 1, 1, 2, false, "x").valid);
        assertFalse(snapshot("uid", "a-f-G", 91, 0, 0, 0, 0, 1, 1, 2, false, "x").valid);
        assertFalse(snapshot("uid", "a-f-G", 0, -181, 0, 0, 0, 1, 1, 2, false, "x").valid);
        assertFalse(snapshot("uid", "a-f-G", Double.NaN, 0, 0, 0, 0, 1, 1, 2, false, "x").valid);
        assertFalse(snapshot("uid", "a-f-G", 0, Double.POSITIVE_INFINITY, 0, 0, 0, 1, 1, 2, false, "x").valid);
        assertFalse(snapshot("uid", "a-f-G", 0, 0, Double.NaN, 0, 0, 1, 1, 2, false, "x").valid);
        assertFalse(snapshot("uid", "a-f-G", 0, 0, 0, Double.NaN, 0, 1, 1, 2, false, "x").valid);
        assertFalse(snapshot("uid", "a-f-G", 0, 0, 0, 0, Double.POSITIVE_INFINITY, 1, 1, 2, false, "x").valid);
        assertFalse(snapshot("uid", "a-f-G", 0, 0, 0, 0, 0, 0, 1, 2, false, "x").valid);
        assertFalse(snapshot("uid", "a-f-G", 0, 0, 0, 0, 0, 1, -1, 2, false, "x").valid);
        assertFalse(snapshot("uid", "a-f-G", 0, 0, 0, 0, 0, 1, 1, 0, false, "x").valid);
        assertFalse(snapshot("uid", "a-f-G", 0, 0, 0, 0, 0, 1, 1, 2, true, "x").valid);
        assertTrue(snapshot("uid", "a-f-G", -90, 180, 0, 9999999, 9999999, 1, 1, 2, false, "x").valid);
    }

    @Test public void disconnectedOrSupersededSessionCannotWriteOrReplay() throws Exception {
        PhoneMarkerRelay relay = new PhoneMarkerRelay();
        PhoneMarkerRelay.Writer failIfCalled = (json, session) -> {
            fail("No writes while disconnected or in another session");
            return false;
        };
        assertFalse(relay.forward(marker("x"), -1, -1, 0, failIfCalled));
        assertFalse(relay.forward(marker("x"), 7, 8, 0, failIfCalled));
        assertFalse(relay.forward(marker("x"), -1, 8, 0, failIfCalled));
        List<String> writes = new ArrayList<>();
        assertEquals(0, writes.size()); // Changing sessions alone never initiates a send.
        assertTrue(relay.forward(marker("x"), 8, 8, 0, (json, session) -> writes.add(json)));
        assertEquals(1, writes.size());
    }

    @Test public void watchEchoIsSuppressedButPhoneEditsToSameUidAreNot() throws Exception {
        PhoneMarkerRelay relay = new PhoneMarkerRelay();
        relay.watchImportStarted("phone", 0);
        assertFalse(relay.forward(marker("watch"), 7, 7, 1, (json, session) -> true));
        relay.watchImported(marker("watch"));
        assertFalse(relay.forward(marker("watch"), 7, 7, 2, (json, session) -> true));
        assertTrue(relay.forward(marker("phone edit"), 7, 7, 3, (json, session) -> true));
        assertTrue(relay.forward(marker("watch"), 7, 7, 4, (json, session) -> true));
    }

    @Test public void watchEchoSuppressionSurvivesReconnectWithoutBulkSync() throws Exception {
        PhoneMarkerRelay relay = new PhoneMarkerRelay();
        relay.watchImported(marker("watch"));
        assertFalse(relay.forward(marker("watch"), 8, 8, 1, (json, session) -> true));
        assertTrue(relay.forward(marker("edited"), 8, 8, 2, (json, session) -> true));
    }

    @Test public void failedImportDoesNotBlacklistUidForever() throws Exception {
        PhoneMarkerRelay relay = new PhoneMarkerRelay();
        relay.watchImportStarted("phone", 0);
        assertTrue(relay.forward(marker("phone edit"), 7, 7, 5000, (json, session) -> true));
    }

    @Test public void rejectedWriteDoesNotRecordSuccessfulDelivery() throws Exception {
        PhoneMarkerRelay relay = new PhoneMarkerRelay();
        assertFalse(relay.forward(marker("x"), 7, 7, 0, (json, session) -> false));
        assertTrue(relay.forward(marker("x"), 7, 7, 0, (json, session) -> true));
    }

    @Test public void thrownWriteFailureDoesNotRecordSuccessfulDelivery() throws Exception {
        PhoneMarkerRelay relay = new PhoneMarkerRelay();
        try {
            relay.forward(marker("x"), 7, 7, 0, (json, session) -> {
                throw new IllegalStateException("GATT rejected");
            });
            fail("Expected write exception");
        } catch (IllegalStateException expected) {
            assertEquals("GATT rejected", expected.getMessage());
        }
        assertTrue(relay.forward(marker("x"), 7, 7, 0, (json, session) -> true));
    }

    @Test public void removalClearsUidStateWithoutSendingAnUnsupportedDelete() throws Exception {
        PhoneMarkerRelay relay = new PhoneMarkerRelay();
        relay.watchImported(marker("watch"));
        relay.removed("phone");
        assertTrue(relay.forward(marker("watch"), 7, 7, 0, (json, session) -> true));
    }

    @Test public void stopClearsAllPolicyState() throws Exception {
        PhoneMarkerRelay relay = new PhoneMarkerRelay();
        relay.watchImported(marker("watch"));
        relay.watchImportStarted("phone", 0);
        relay.clear();
        assertTrue(relay.forward(marker("watch"), 8, 8, 0, (json, session) -> true));
    }
}
