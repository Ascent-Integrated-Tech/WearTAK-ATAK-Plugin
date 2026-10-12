package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

final class ChatRelayPayloadBuilder {
    private ChatRelayPayloadBuilder() {}

    static JSONObject build(String selfUid, String senderUid, String messageId,
                            String senderCallsign, String text, long receivedAt,
                            String conversationUid, String conversationTitle) throws JSONException {
        return build(selfUid, senderUid, messageId, senderCallsign, text, receivedAt,
                conversationUid, conversationTitle, false, null);
    }

    static JSONObject build(String selfUid, String senderUid, String messageId,
                            String senderCallsign, String text, long receivedAt,
                            String conversationUid, String conversationTitle,
                            boolean groupContact, String[] destinations) throws JSONException {
        if (senderUid == null || senderUid.isEmpty() || senderUid.equals(selfUid)
                || messageId == null || messageId.isEmpty()
                || senderCallsign == null || senderCallsign.isEmpty()
                || text == null || text.isEmpty()) {
            return null;
        }
        ChatConversation conversation = new ChatConversation(conversationUid, conversationTitle,
                senderUid, senderCallsign, selfUid, groupContact);
        boolean broadcast = "All Chat Rooms".equals(conversation.uid);
        if (!broadcast && (conversation.group || destinations != null)
                && (destinations == null || !java.util.Arrays.asList(destinations).contains(selfUid))) {
            throw new IllegalArgumentException("Chat recipients do not include the paired phone");
        }
        JSONObject payload = new JSONObject();
        payload.put("uid", messageId);
        payload.put("senderUid", senderUid);
        payload.put("senderCallsign", senderCallsign);
        payload.put("msg", text);
        payload.put("receivedAt", receivedAt);
        payload.put("conversationUid", conversation.uid);
        payload.put("conversationTitle", conversation.title);
        payload.put("isGroup", conversation.group);
        if (conversation.group) {
            java.util.Set<String> members = new java.util.LinkedHashSet<>();
            members.add(senderUid);
            if (destinations != null) {
                for (String uid : destinations) {
                    if (uid != null && !uid.trim().isEmpty() && !uid.equals(conversation.uid)) members.add(uid);
                }
            }
            payload.put("participantUids", new JSONArray(members));
        }
        return new JSONObject()
                .put("vers", 1)
                .put("msgType", "chat_received")
                .put("msgUid", messageId)
                .put("src", "atak")
                .put("payload", payload);
    }
}
