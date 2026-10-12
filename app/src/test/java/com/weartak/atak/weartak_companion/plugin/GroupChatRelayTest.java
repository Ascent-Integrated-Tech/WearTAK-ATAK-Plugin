package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class GroupChatRelayTest {
    private static final class Targets implements ChatReplyRouter.Targets<String> {
        final List<String> sends = new ArrayList<>();
        boolean writable = true;
        boolean privateContact = true;
        List<String> members = Arrays.asList("alice", "bob");
        public String find(String uid) { return "team".equals(uid) || "alice".equals(uid) ? uid : null; }
        public boolean isGroup(String target) { return "team".equals(target); }
        public boolean isPrivateContact(String target) { return privateContact && "alice".equals(target); }
        public void sendDirect(String text, String target) { sends.add("direct:" + target + ":" + text); }
        public boolean isWritable(String group) { return writable; }
        public List<String> recipients(String group) { return members; }
        public void sendGroup(String text, String group, String[] recipients) {
            assertArrayEquals(members.toArray(new String[0]), recipients);
            sends.add(group + ":" + text);
        }
        public void sendBroadcast(String text) { sends.add("All Chat Rooms:" + text); }
    }

    @Test public void receivedGroupIdentityIsTheReplyApiTargetNotTheSender() throws Exception {
        Targets targets = new Targets();
        for (String sender : Arrays.asList("alice", "bob")) {
            JSONObject envelope = ChatRelayPayloadBuilder.build("phone", sender, "m-" + sender,
                    sender, "Hi", 123, "team", "Team", true, new String[]{"phone", "alice", "bob"});
            JSONObject payload = envelope.getJSONObject("payload");
            assertEquals(sender, payload.getString("senderUid"));
            assertEquals("team", payload.getString("conversationUid"));
            assertEquals("Team", payload.getString("conversationTitle"));
            assertTrue(payload.getBoolean("isGroup"));
            assertEquals(3, payload.getJSONArray("participantUids").length());
            assertTrue(ChatReplyRouter.route(payload.getString("conversationUid"),
                    payload.getBoolean("isGroup"), "Reply", targets));
        }
        assertEquals(Arrays.asList("team:Reply", "team:Reply"), targets.sends);
    }

    @Test public void legacyDirectAndKnownGroupTargetsAreResolvedWithoutBroadcastFallback() throws Exception {
        Targets targets = new Targets();
        for (String uid : new String[]{null, "", "phone", "alice"}) {
            JSONObject payload = ChatRelayPayloadBuilder.build("phone", "alice", "m", "Alice",
                    "Hi", 100, uid, "ignored").getJSONObject("payload");
            assertEquals("alice", payload.getString("conversationUid"));
            assertEquals("Alice", payload.getString("conversationTitle"));
            assertFalse(payload.getBoolean("isGroup"));
            assertTrue(ChatReplyRouter.route("alice", false, "Direct", targets));
        }
        assertTrue(ChatReplyRouter.route("team", false, "Legacy group reply", targets));
        assertEquals(Arrays.asList("direct:alice:Direct", "direct:alice:Direct",
                "direct:alice:Direct", "direct:alice:Direct", "team:Legacy group reply"), targets.sends);
    }

    @Test public void unknownSelfAndBroadcastLikePrivateContactsNeverSend() {
        Targets targets = new Targets();
        for (String uid : new String[]{"missing", "phone", "", " ", "all chat rooms"}) {
            assertThrows(IllegalArgumentException.class, () -> ChatReplyRouter.route(uid, false, "Private", targets));
        }
        targets.privateContact = false;
        assertThrows(IllegalArgumentException.class, () -> ChatReplyRouter.route("alice", false, "Private", targets));
        assertTrue(targets.sends.isEmpty());
    }

    @Test public void missingUnknownAndIndividualGroupTargetsNeverSend() {
        Targets targets = new Targets();
        for (String uid : new String[]{null, "", " ", "missing", "alice"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ChatReplyRouter.route(uid, true, "No leak", targets));
        }
        assertThrows(IllegalArgumentException.class, () -> ChatReplyRouter.route("team", true, " ", targets));
        assertTrue(targets.sends.isEmpty());
    }

    @Test public void explicitAllChatRoomsUsesOnlyBroadcastPath() throws Exception {
        Targets targets = new Targets();
        JSONObject payload = ChatRelayPayloadBuilder.build("phone", "alice", "m", "Alice",
                "Hi", 100, "All Chat Rooms", "All Chat Rooms").getJSONObject("payload");
        assertTrue(ChatReplyRouter.route(payload.getString("conversationUid"), payload.getBoolean("isGroup"), "All", targets));
        assertEquals(Arrays.asList("All Chat Rooms:All"), targets.sends);
    }

    @Test public void canonicalReplyEnvelopesDispatchValidatedGroupPrivateAndBroadcastTargets() throws Exception {
        Targets targets = new Targets();
        for (String json : Arrays.asList(
                "{\"roomUid\":\"team\",\"roomTitle\":\"Team\",\"isGroup\":true,\"msg\":\"Group\"}",
                "{\"roomUid\":\"alice\",\"recipientUid\":\"alice\",\"replyTo\":\"alice\",\"text\":\"Private\"}",
                "{\"roomUid\":\"All Chat Rooms\",\"isGroup\":true,\"msg\":\"All\"}")) {
            ChatReplyPayload reply = new ChatReplyPayload(new JSONObject(json));
            ChatReplyRouter.route(reply.roomUid, reply.isGroup, reply.text, targets);
        }
        assertEquals(Arrays.asList("team:Group", "direct:alice:Private", "All Chat Rooms:All"), targets.sends);
        ChatReplyPayload unknown = new ChatReplyPayload(new JSONObject(
                "{\"roomUid\":\"missing\",\"isGroup\":true,\"msg\":\"No leak\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> ChatReplyRouter.route(unknown.roomUid, unknown.isGroup, unknown.text, targets));
        assertEquals(3, targets.sends.size());
    }

    @Test public void emptyOrUnwritableGroupsNeverInvokeSendOrBroadcast() {
        Targets targets = new Targets();
        targets.members = java.util.Collections.emptyList();
        assertThrows(IllegalArgumentException.class, () -> ChatReplyRouter.route("team", true, "No leak", targets));
        targets.members = Arrays.asList("alice", "bob");
        targets.writable = false;
        assertThrows(IllegalArgumentException.class, () -> ChatReplyRouter.route("team", true, "No leak", targets));
        assertTrue(targets.sends.isEmpty());
    }

    @Test public void unknownForeignConversationMissingTitleAndPhoneEchoAreNotRelayed() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ChatRelayPayloadBuilder.build("phone",
                "alice", "m", "Alice", "Private", 100, "other-person", "Other"));
        assertThrows(IllegalArgumentException.class, () -> ChatRelayPayloadBuilder.build("phone",
                "alice", "m", "Alice", "Hi", 100, "team", "", true, new String[]{"phone"}));
        assertNull(ChatRelayPayloadBuilder.build("phone", "phone", "m", "PHONE", "Echo", 100,
                "team", "Team", true, new String[]{"alice"}));
        assertThrows(IllegalArgumentException.class, () -> ChatRelayPayloadBuilder.build("phone",
                "alice", "m", "Alice", "Private group", 100, "team", "Team", true, new String[]{"alice", "bob"}));
        assertThrows(IllegalArgumentException.class, () -> ChatRelayPayloadBuilder.build("phone",
                "alice", "m", "Alice", "Private direct", 100, "alice", "Alice", false, new String[]{"alice", "bob"}));
    }
}
