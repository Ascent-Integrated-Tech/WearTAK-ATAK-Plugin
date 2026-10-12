package com.weartak.atak.weartak_companion.plugin;

final class ChatConversation {
    final String uid;
    final String title;
    final boolean group;

    ChatConversation(String conversationUid, String conversationTitle,
                     String senderUid, String senderCallsign, String phoneUid,
                     boolean groupContact) {
        boolean direct = conversationUid == null || conversationUid.trim().isEmpty()
                || conversationUid.equals(senderUid) || conversationUid.equals(phoneUid);
        group = !direct && (groupContact || "All Chat Rooms".equals(conversationUid));
        if (!direct && !group) {
            throw new IllegalArgumentException("Conversation is not a known ATAK group or local direct chat");
        }
        uid = group ? conversationUid : senderUid;
        title = group ? conversationTitle : senderCallsign;
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing conversation title");
        }
    }
}
