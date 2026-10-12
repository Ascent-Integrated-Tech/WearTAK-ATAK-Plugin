package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;

final class ChatReplyPayload {
    final String roomUid;
    final String text;
    final boolean isGroup;
    final boolean hasDirectRecipient;

    ChatReplyPayload(JSONObject payload) {
        isGroup = payload.optBoolean("isGroup", false);
        String room = matching(payload, "roomUid", "rmUid");
        String direct = matching(payload, "recipientUid", "replyTo");
        hasDirectRecipient = !direct.isEmpty();
        if (!room.isEmpty() && !direct.isEmpty() && !room.equals(direct)) {
            throw new IllegalArgumentException("Conflicting chat destination identities");
        }
        if (isGroup && !direct.isEmpty()) {
            throw new IllegalArgumentException("Group chat cannot use a direct-recipient fallback");
        }
        roomUid = room.isEmpty() && !isGroup ? direct : room;
        text = matching(payload, "msg", "text");
        if (roomUid.trim().isEmpty() || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing chat destination or text");
        }
        if ("All Chat Rooms".equals(roomUid) && !direct.isEmpty()) {
            throw new IllegalArgumentException("Direct recipient cannot be All Chat Rooms");
        }
    }

    private static String matching(JSONObject payload, String first, String second) {
        String a = value(payload, first);
        String b = value(payload, second);
        if (!a.isEmpty() && !b.isEmpty() && !a.equals(b)) {
            throw new IllegalArgumentException("Conflicting chat fields: " + first + "/" + second);
        }
        return a.isEmpty() ? b : a;
    }

    private static String value(JSONObject payload, String key) {
        if (!payload.has(key) || payload.isNull(key)) return "";
        Object value = payload.opt(key);
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("Chat field must be a string: " + key);
        }
        return (String) value;
    }
}
