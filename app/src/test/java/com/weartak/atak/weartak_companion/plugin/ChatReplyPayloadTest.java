package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChatReplyPayloadTest {
    @Test public void canonicalGroupAndLegacyRoomFieldsKeepTheSameTarget() throws Exception {
        ChatReplyPayload canonical = new ChatReplyPayload(new JSONObject(
                "{\"roomUid\":\"team\",\"roomTitle\":\"Team\",\"isGroup\":true,\"msg\":\"Hi\",\"cs\":\"WATCH\"}"));
        assertEquals("team", canonical.roomUid);
        assertTrue(canonical.isGroup);
        ChatReplyPayload legacy = new ChatReplyPayload(new JSONObject(
                "{\"rmUid\":\"team\",\"rmTitle\":\"Team\",\"isGroup\":true,\"text\":\"Hi\"}"));
        assertEquals(canonical.roomUid, legacy.roomUid);
        assertEquals(canonical.text, legacy.text);
    }

    @Test public void directAliasesRemainPrivateAndLegacyMissingGroupFlagWorks() throws Exception {
        for (String fields : new String[]{
                "\"roomUid\":\"alice\",\"recipientUid\":\"alice\",\"replyTo\":\"alice\"",
                "\"rmUid\":\"alice\"", "\"replyTo\":\"alice\"", "\"recipientUid\":\"alice\""}) {
            ChatReplyPayload reply = new ChatReplyPayload(new JSONObject("{" + fields + ",\"text\":\"Hi\"}"));
            assertEquals("alice", reply.roomUid);
            assertFalse(reply.isGroup);
        }
    }

    @Test public void conflictsMalformedAndMissingGroupTargetsAreRejected() {
        for (String json : new String[]{
                "{\"roomUid\":\"team\",\"rmUid\":\"All Chat Rooms\",\"msg\":\"Hi\"}",
                "{\"roomUid\":\"team\",\"recipientUid\":\"alice\",\"msg\":\"Hi\"}",
                "{\"isGroup\":true,\"replyTo\":\"alice\",\"msg\":\"Hi\"}",
                "{\"roomUid\":\"team\",\"isGroup\":true,\"recipientUid\":\"team\",\"msg\":\"Hi\"}",
                "{\"roomUid\":\"alice\",\"msg\":\"Hi\",\"text\":\"Other\"}",
                "{\"roomUid\":42,\"msg\":\"Hi\"}",
                "{\"roomUid\":\"All Chat Rooms\",\"replyTo\":\"All Chat Rooms\",\"msg\":\"Hi\"}",
                "{\"isGroup\":true,\"msg\":\"Hi\"}"}) {
            assertThrows(IllegalArgumentException.class, () -> new ChatReplyPayload(new JSONObject(json)));
        }
    }

    @Test public void explicitAllChatRoomsRetainsCanonicalBroadcastIdentity() throws Exception {
        ChatReplyPayload reply = new ChatReplyPayload(new JSONObject(
                "{\"roomUid\":\"All Chat Rooms\",\"isGroup\":true,\"msg\":\"All\"}"));
        assertEquals("All Chat Rooms", reply.roomUid);
        assertTrue(reply.isGroup);
    }

    @Test public void messageEventIdIsNeverUsedAsMissingReplyDestination() {
        for (String json : new String[]{
                "{\"uid\":\"event-message-id\",\"msg\":\"Private\"}",
                "{\"uid\":\"event-message-id\",\"roomUid\":\"\",\"msg\":\"Private\"}",
                "{\"uid\":\"event-message-id\",\"senderUid\":\"\",\"isGroup\":true,\"msg\":\"Private\"}"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ChatReplyPayload(new JSONObject(json)));
        }
    }
}
