package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class ChatRelayPayloadBuilderTest {
    @Test
    public void preservesExistingChatEnvelopeAndSenderIdentity() throws Exception {
        JSONObject envelope = ChatRelayPayloadBuilder.build("phone", "sender", "message",
                "SENDER", "Hello \"watch\"", 1234, "All Chat Rooms", "All Chat Rooms");
        assertEquals(1, envelope.getInt("vers"));
        assertEquals("chat_received", envelope.getString("msgType"));
        assertEquals("message", envelope.getString("msgUid"));
        assertEquals("atak", envelope.getString("src"));
        JSONObject payload = envelope.getJSONObject("payload");
        assertEquals("message", payload.getString("uid"));
        assertEquals("sender", payload.getString("senderUid"));
        assertEquals("SENDER", payload.getString("senderCallsign"));
        assertEquals("Hello \"watch\"", payload.getString("msg"));
        assertEquals(1234, payload.getLong("receivedAt"));
        assertEquals("All Chat Rooms", payload.getString("conversationUid"));
        assertEquals("All Chat Rooms", payload.getString("conversationTitle"));
    }

    @Test
    public void suppressesPairedPhoneAndWatchAttributedToPhoneEchoes() throws Exception {
        assertNull(ChatRelayPayloadBuilder.build("phone", "phone", "message",
                "PHONE", "Echo", 1234, "", ""));
    }

    @Test
    public void rejectsIncompleteIncomingMessages() throws Exception {
        assertNull(ChatRelayPayloadBuilder.build("phone", null, "message", "SENDER", "Hi", 0, "", ""));
        assertNull(ChatRelayPayloadBuilder.build("phone", "", "message", "SENDER", "Hi", 0, "", ""));
        assertNull(ChatRelayPayloadBuilder.build("phone", "sender", "", "SENDER", "Hi", 0, "", ""));
        assertNull(ChatRelayPayloadBuilder.build("phone", "sender", "message", null, "Hi", 0, "", ""));
        assertNull(ChatRelayPayloadBuilder.build("phone", "sender", "message", "SENDER", "", 0, "", ""));
    }
}
