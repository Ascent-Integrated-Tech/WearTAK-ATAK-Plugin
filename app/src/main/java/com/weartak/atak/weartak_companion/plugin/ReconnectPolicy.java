package com.weartak.atak.weartak_companion.plugin;

final class ReconnectPolicy {

    static final long INITIAL_DELAY_MS = 2_000L;
    static final long RETRY_DELAY_MS = 60_000L;
    static final long MAX_DELAY_MS = 15 * 60_000L;

    private ReconnectPolicy() { }

    static long delayForAttempt(int attempt) {
        if (attempt <= 0) return INITIAL_DELAY_MS;
        int shift = Math.min(attempt - 1, 4);
        return Math.min(MAX_DELAY_MS, RETRY_DELAY_MS * (1L << shift));
    }

    static String normalizeDeviceName(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.startsWith("WT-")) normalized = normalized.substring(3);
        if (normalized.startsWith("WEAROS-")) normalized = normalized.substring(7);
        normalized = normalized.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
