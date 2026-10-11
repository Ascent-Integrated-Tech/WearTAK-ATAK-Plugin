package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class PhoneRelayIntegrationTest {
    private static final String ALERT = "<event uid='shared' type='b-a-o'/>";
    private static final String CANCEL = "<event uid='shared' type='b-a-o-can'/>";

    private PhoneMarkerRelay.Snapshot marker(String fingerprint) {
        return new PhoneMarkerRelay.Snapshot("shared", "a-f-G", 38, -77, 0,
                9999999, 9999999, 1, 1, 2, false, fingerprint,
                "<event uid='shared' type='a-f-G'><detail><remarks>"
                        + fingerprint + "</remarks></detail></event>");
    }

    @Test public void sharedQueuePreservesPointsChatSettingsAlertsAndCancellation() throws Exception {
        PhoneMarkerRelay points = new PhoneMarkerRelay();
        PhoneEmergencyRelay emergencies = new PhoneEmergencyRelay();
        emergencies.start();
        List<String> queue = new ArrayList<>();

        assertTrue(points.forward(marker("initial"), 7, 7, 0,
                (json, session) -> queue.add(json)));
        queue.add(ChatRelayPayloadBuilder.build("self", "contact", "chat-id", "CONTACT",
                "hello", 1, "conversation", "Conversation").toString());
        queue.add(SettingsPayloadBuilder.build(Collections.emptyList(),
                null, null, null, null, false).toString());
        assertTrue(emergencies.forward("b-a-o", ALERT, true, false, queue::add));
        assertTrue(emergencies.forward("b-a-o", ALERT, true, false, queue::add));
        assertTrue(emergencies.forward("b-a-o-can", CANCEL, true, false, queue::add));
        assertFalse(points.forward(marker("initial"), 7, 7, 1,
                (json, session) -> queue.add(json)));
        assertTrue(points.forward(marker("edited"), 7, 7, 2,
                (json, session) -> queue.add(json)));

        assertEquals(6, queue.size());
        assertEquals("phone_marker_cot", new JSONObject(queue.get(0)).getString("msg_type"));
        assertEquals("chat_received", new JSONObject(queue.get(1)).getString("msgType"));
        JSONObject settings = new JSONObject(queue.get(2));
        assertEquals("set_settings", settings.getString("msg_type"));
        assertFalse(settings.getJSONObject("payload").getBoolean("phoneManagedSettings"));
        assertEquals(ALERT, new JSONObject(queue.get(3)).getJSONObject("payload").getString("cot"));
        assertEquals(CANCEL, new JSONObject(queue.get(4)).getJSONObject("payload").getString("cot"));
        assertEquals(marker("edited").xml,
                new JSONObject(queue.get(5)).getJSONObject("payload").getString("cot"));
    }

    @Test public void echoGuardsAndStopRestartStateRemainIndependent() throws Exception {
        PhoneMarkerRelay points = new PhoneMarkerRelay();
        PhoneEmergencyRelay emergencies = new PhoneEmergencyRelay();
        emergencies.start();
        List<String> queue = new ArrayList<>();
        points.watchImported(marker("watch"));
        assertFalse(points.forward(marker("watch"), 7, 7, 0,
                (json, session) -> queue.add(json)));
        assertTrue(emergencies.forward("b-a-o", ALERT, true, true, queue::add));
        assertTrue(emergencies.forward("b-a-o-can", CANCEL, true, true, queue::add));
        assertNull(ChatRelayPayloadBuilder.build("self", "self", "watch-chat", "SELF",
                "echo", 1, "", ""));
        assertTrue(queue.isEmpty());
        assertTrue(points.forward(marker("phone edit"), 7, 7, 1,
                (json, session) -> queue.add(json)));
        assertTrue(emergencies.forward("b-a-o", ALERT, true, false, queue::add));

        points.clear();
        emergencies.stop();
        assertFalse(points.forward(marker("stopped edit"), 7, -1, 2,
                (json, session) -> queue.add(json)));
        assertTrue(emergencies.forward("b-a-o-can", CANCEL, true, false, queue::add));
        assertEquals(2, queue.size());
        emergencies.start();
        assertEquals(2, queue.size());
        assertTrue(points.forward(marker("phone edit"), 8, 8, 3,
                (json, session) -> queue.add(json)));
        assertTrue(emergencies.forward("b-a-o", ALERT, true, false, queue::add));
        assertEquals(4, queue.size());
    }
}
