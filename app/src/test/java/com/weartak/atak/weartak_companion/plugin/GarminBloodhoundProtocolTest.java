package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

public class GarminBloodhoundProtocolTest {
    private static final String RELAY = "phone-link-1";
    private static final String SESSION = "11111111-1111-4111-8111-111111111111";
    private static final String COMMAND = "22222222-2222-4222-8222-222222222222";

    private JSONObject payload(String action) throws Exception {
        return new JSONObject().put("relaySession", RELAY).put("bloodhoundVersion", 1)
                .put("command_id", COMMAND).put("session_id", SESSION)
                .put("target_uid", "remote-1").put("action", action)
                .put("target_callsign", "Rescue").put("lat", 38.5).put("lon", -77.2);
    }

    @Test public void normalizesAndSerializesCorrelatedControlFrame() throws Exception {
        JSONObject incoming = new JSONObject().put("msgType", "bloodhound_control")
                .put("payload", payload("start"));
        JSONObject normalized = GarminBloodhoundProtocol.inbound(incoming, RELAY);
        assertEquals("bloodhound_control", normalized.optString("msg_type"));
        assertEquals("start", normalized.getJSONObject("payload").optString("action"));
        assertEquals(COMMAND, normalized.getJSONObject("payload").optString("command_id"));

        JSONObject response = new JSONObject().put("msg_type", "bloodhound_control_result")
                .put("payload", payload("start").put("ok", true));
        assertEquals("bloodhound_control_result", GarminBloodhoundProtocol.outboundType(response));
        Map<String, Object> values = GarminBloodhoundProtocol.outboundPayload(response);
        assertEquals(RELAY, values.get("relaySession"));
        assertEquals(1, values.get("bloodhoundVersion"));
        assertEquals(COMMAND, values.get("command_id"));
        assertEquals(true, values.get("ok"));
        UUID.fromString((String) values.get("session_id"));
    }

    @Test public void rejectsWrongRelayUnsupportedVersionAndMalformedIdentity() throws Exception {
        JSONObject valid = new JSONObject().put("msgType", "bloodhound_control")
                .put("payload", payload("start"));
        for (JSONObject malformed : new JSONObject[]{
                new JSONObject(valid.toString()).put("payload", payload("start").put("relaySession", "old")),
                new JSONObject(valid.toString()).put("payload", payload("start").put("bloodhoundVersion", "1")),
                new JSONObject(valid.toString()).put("payload", payload("start").put("bloodhoundVersion", 1.5d)),
                new JSONObject(valid.toString()).put("payload", payload("start").put("command_id", "bad")),
                new JSONObject(valid.toString()).put("payload", payload("start").put("session_id", "bad")),
                new JSONObject(valid.toString()).put("payload", payload("start").put("target_uid", 1)),
                new JSONObject(valid.toString()).put("payload", payload("move").put("target_uid", "x\n")),
                new JSONObject(valid.toString()).put("payload", payload("launch"))
        }) {
            try {
                GarminBloodhoundProtocol.inbound(malformed, RELAY);
                fail("Malformed Garmin Bloodhound frame was accepted");
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test public void requiresBooleanResultAndRejectsUnrelatedTypes() throws Exception {
        JSONObject result = new JSONObject().put("msgType", "bloodhound_control_result")
                .put("payload", payload("stop"));
        try {
            GarminBloodhoundProtocol.inbound(result, RELAY);
            fail("Result without status was accepted");
        } catch (IllegalArgumentException expected) {
            assertEquals("Missing Bloodhound result status", expected.getMessage());
        }
        try {
            GarminBloodhoundProtocol.outboundType(
                    new JSONObject().put("msg_type", "marker").put("payload", payload("start")));
            fail("Unrelated message type was accepted");
        } catch (IllegalArgumentException expected) {
            assertEquals("Unsupported Bloodhound message", expected.getMessage());
        }
    }
}
