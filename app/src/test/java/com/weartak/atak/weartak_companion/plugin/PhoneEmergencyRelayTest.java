package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class PhoneEmergencyRelayTest {
    private static final String ALERT = "<event uid='phone-911' type='b-a-o' time='2026-10-10T12:00:00Z'>"
            + "<point lat='35.1' lon='-106.2'/><detail><contact callsign='PHONE'/>"
            + "<emergency type='911'>PHONE</emergency></detail></event>";
    private static final String CANCEL = "<event uid='phone-911' type='b-a-o-can'>"
            + "<detail><emergency cancel='true'>PHONE</emergency></detail></event>";

    @Test
    public void preservesAlertAndCancellationAsDistinctOriginalCotPayloads() throws Exception {
        PhoneEmergencyRelay relay = new PhoneEmergencyRelay();
        relay.start();
        List<String> sent = new ArrayList<>();
        PhoneEmergencyRelay.Sender sender = json -> sent.add(json);
        assertTrue(relay.forward("b-a-o", ALERT, true, false, sender));
        assertTrue(relay.forward("b-a-o-can", CANCEL, true, false, sender));
        assertEquals(2, sent.size());
        JSONObject alert = new JSONObject(sent.get(0));
        assertEquals("phone_emergency_cot", alert.getString("msg_type"));
        assertEquals(ALERT, alert.getJSONObject("payload").getString("cot"));
        assertEquals(CANCEL, new JSONObject(sent.get(1)).getJSONObject("payload").getString("cot"));
    }

    @Test
    public void acceptsEmergencyTypesButNotChatPointsOrDeletion() {
        for (String type : new String[]{"b-a-o", "b-a-o-tbl", "b-a-o-can", "b-a-g"}) {
            assertTrue(type, PhoneEmergencyRelay.isEmergencyType(type));
        }
        for (String type : new String[]{null, "", "b-a", "b-a-other", "b-a-g-other",
                "b-t-f", "a-f-G", "t-x-d-d"}) {
            assertFalse(type, PhoneEmergencyRelay.isEmergencyType(type));
        }
    }

    @Test
    public void suppressesWatchEchoesAlertsAndCancels() throws Exception {
        PhoneEmergencyRelay relay = new PhoneEmergencyRelay();
        relay.start();
        List<String> sent = new ArrayList<>();
        relay.forward("b-a-o", ALERT, true, true, sent::add);
        relay.forward("b-a-o-can", CANCEL, true, true, sent::add);
        assertTrue(sent.isEmpty());
    }

    @Test
    public void stoppedAndDisconnectedAlertsAreNotReplayed() throws Exception {
        PhoneEmergencyRelay relay = new PhoneEmergencyRelay();
        List<String> sent = new ArrayList<>();
        relay.forward("b-a-o", ALERT, true, false, sent::add);
        relay.start();
        relay.forward("b-a-o", ALERT, false, false, sent::add);
        relay.forward("b-a-o-can", CANCEL, false, false, sent::add);
        assertTrue(sent.isEmpty());
        relay.forward("b-t-f", "chat", true, false, sent::add);
        assertTrue(sent.isEmpty());
        relay.stop();
        relay.forward("b-a-o", ALERT, true, false, sent::add);
        assertTrue(sent.isEmpty());
        relay.start();
        assertTrue(sent.isEmpty());
        relay.forward("b-a-o-can", CANCEL, true, false, sent::add);
        assertEquals(1, sent.size());
    }

    @Test
    public void duplicateDispatchIsSuppressedWithoutSuppressingSameUidCancel() throws Exception {
        PhoneEmergencyRelay relay = new PhoneEmergencyRelay();
        relay.start();
        List<String> sent = new ArrayList<>();
        relay.forward("b-a-o", ALERT, true, false, sent::add);
        relay.start();
        relay.forward("b-a-o", ALERT, true, false, sent::add);
        relay.forward("b-a-o-can", CANCEL, true, false, sent::add);
        assertEquals(2, sent.size());
    }

    @Test
    public void reportsAlertAndCancelQueueFailuresAndAllowsRetry() throws Exception {
        PhoneEmergencyRelay relay = new PhoneEmergencyRelay();
        relay.start();
        assertFalse(relay.forward("b-a-o", ALERT, true, false, json -> false));
        assertFalse(relay.forward("b-a-o-can", CANCEL, true, false, json -> false));
        List<String> sent = new ArrayList<>();
        assertTrue(relay.forward("b-a-o", ALERT, true, false, sent::add));
        assertTrue(relay.forward("b-a-o-can", CANCEL, true, false, sent::add));
        assertEquals(2, sent.size());
    }

    @Test
    public void duplicateHistoryIsBounded() throws Exception {
        PhoneEmergencyRelay relay = new PhoneEmergencyRelay();
        relay.start();
        List<String> sent = new ArrayList<>();
        for (int i = 0; i <= 256; i++) {
            relay.forward("b-a-o", "event-" + i, true, false, sent::add);
        }
        relay.forward("b-a-o", "event-0", true, false, sent::add);
        assertEquals(258, sent.size());
    }
}
