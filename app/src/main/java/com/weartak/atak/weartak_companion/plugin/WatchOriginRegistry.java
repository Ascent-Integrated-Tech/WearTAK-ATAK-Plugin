package com.weartak.atak.weartak_companion.plugin;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks watch-originated events so ATAK callbacks are not echoed to the watch. */
final class WatchOriginRegistry {

    private static final Set<String> MARKERS = ConcurrentHashMap.newKeySet();
    private static final Set<String> CHATS = ConcurrentHashMap.newKeySet();
    private static final Set<String> EMERGENCIES = ConcurrentHashMap.newKeySet();

    private WatchOriginRegistry() {
    }

    static void markMarker(String uid) {
        addIfPresent(MARKERS, uid);
    }

    static boolean consumeMarker(String uid) {
        return removeIfPresent(MARKERS, uid);
    }

    static boolean isMarker(String uid) {
        return containsIfPresent(MARKERS, uid);
    }

    static void markChat(String uid) {
        addIfPresent(CHATS, uid);
    }

    static boolean consumeChat(String uid) {
        return removeIfPresent(CHATS, uid);
    }

    static void markEmergency(String uid) {
        addIfPresent(EMERGENCIES, uid);
    }

    static boolean consumeEmergency(String uid) {
        return removeIfPresent(EMERGENCIES, uid);
    }

    static boolean isEmergency(String uid) {
        return containsIfPresent(EMERGENCIES, uid);
    }

    static void clearEmergency(String uid) {
        removeIfPresent(EMERGENCIES, uid);
    }

    private static void addIfPresent(Set<String> values, String value) {
        if (value != null && !value.isEmpty()) values.add(value);
    }

    private static boolean removeIfPresent(Set<String> values, String value) {
        return value != null && !value.isEmpty() && values.remove(value);
    }

    private static boolean containsIfPresent(Set<String> values, String value) {
        return value != null && !value.isEmpty() && values.contains(value);
    }
}
