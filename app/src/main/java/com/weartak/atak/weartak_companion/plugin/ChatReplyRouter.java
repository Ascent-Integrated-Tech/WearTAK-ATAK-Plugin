package com.weartak.atak.weartak_companion.plugin;

final class ChatReplyRouter {
    interface Targets<T> {
        T find(String uid);
        boolean isGroup(T target);
        boolean isWritable(T group);
        java.util.List<String> recipients(T group);
        void sendGroup(String text, T group, String[] recipients);
        void sendBroadcast(String text);
    }

    private ChatReplyRouter() {}

    static <T> boolean route(String uid, boolean requestedGroup, String text, Targets<T> targets) {
        if (uid == null || uid.trim().isEmpty() || text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing chat destination or text");
        }
        if ("All Chat Rooms".equals(uid)) {
            targets.sendBroadcast(text);
            return true;
        }
        T target = targets.find(uid);
        if (requestedGroup || (target != null && targets.isGroup(target))) {
            if (target == null || !targets.isGroup(target)) {
                throw new IllegalArgumentException("ATAK group destination is unavailable: " + uid);
            }
            if (!targets.isWritable(target)) {
                throw new IllegalArgumentException("ATAK group is not writable: " + uid);
            }
            java.util.List<String> recipients = targets.recipients(target);
            if (recipients.isEmpty()) {
                throw new IllegalArgumentException("ATAK group has no available recipients");
            }
            targets.sendGroup(text, target, recipients.toArray(new String[0]));
            return true;
        }
        return false;
    }
}
