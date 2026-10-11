package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONException;
import org.json.JSONObject;

final class ChatRelayPayloadBuilder {
    private ChatRelayPayloadBuilder() {}

    static JSONObject build(String selfUid, String senderUid, String messageId,
                            String senderCallsign, String text, long receivedAt,
                            String conversationUid, String conversationTitle) throws JSONException {
        if (senderUid == null || senderUid.isEmpty() || senderUid.equals(selfUid)
                || messageId == null || messageId.isEmpty()
                || senderCallsign == null || senderCallsign.isEmpty()
                || text == null || text.isEmpty()) {
            return null;
        }
        JSONObject payload = new JSONObject();
        payload.put("uid", messageId);
        payload.put("senderUid", senderUid);
        payload.put("senderCallsign", senderCallsign);
        payload.put("msg", text);
        payload.put("receivedAt", receivedAt);
        payload.put("conversationUid", conversationUid);
        payload.put("conversationTitle", conversationTitle);
        return new JSONObject()
                .put("vers", 1)
                .put("msgType", "chat_received")
                .put("msgUid", messageId)
                .put("src", "atak")
                .put("payload", payload);
    }
}
