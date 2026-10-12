package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class GarminSettingsProtocol {
    private static final String[] PROFILE_FIELDS = {
            "callsign", "team", "role", "reportIntSecs", "dynamicReporting",
            "phoneManagedSettings"
    };

    private GarminSettingsProtocol() { }

    static String newTransactionId() {
        return UUID.randomUUID().toString();
    }

    static boolean isValidTransactionId(Object value) {
        if (!(value instanceof String)) return false;
        String id = (String) value;
        if (id.length() != 36) return false;
        for (int i = 0; i < id.length(); i++) {
            char character = id.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (character != '-') return false;
            } else if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f')
                    || (character >= 'A' && character <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    static Map<String, Object> request(String transactionId) {
        requireTransactionId(transactionId);
        Map<String, Object> payload = new HashMap<>();
        payload.put("transactionId", transactionId);
        return payload;
    }

    static Map<String, Object> phoneSettings(String transactionId, String callsign,
            Integer reportInterval, String team, String role, boolean managed) {
        requireTransactionId(transactionId);
        if (reportInterval != null && (reportInterval < 1 || reportInterval > 3600)) {
            throw new IllegalArgumentException("Reporting interval must be between 1 and 3600 seconds.");
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("transactionId", transactionId);
        if (callsign != null) payload.put("callsign", callsign.trim());
        if (reportInterval != null) payload.put("reportIntSecs", reportInterval);
        if (managed) {
            if (team == null || team.trim().isEmpty() || role == null || role.trim().isEmpty()) {
                throw new IllegalArgumentException("Phone management requires phone team and role.");
            }
            payload.put("team", team.trim());
            payload.put("role", role.trim());
        }
        payload.put("phoneManagedSettings", managed);
        return payload;
    }

    static boolean isCompleteSnapshot(JSONObject snapshot) {
        if (snapshot == null) return false;
        for (String field : PROFILE_FIELDS) {
            if (!snapshot.has(field) || snapshot.isNull(field)) return false;
        }
        if (!(snapshot.opt("callsign") instanceof String)
                || !(snapshot.opt("team") instanceof String)
                || !(snapshot.opt("role") instanceof String)
                || !(snapshot.opt("reportIntSecs") instanceof Number)
                || !(snapshot.opt("dynamicReporting") instanceof Boolean)
                || !(snapshot.opt("phoneManagedSettings") instanceof Boolean)) {
            return false;
        }
        Number interval = (Number) snapshot.opt("reportIntSecs");
        return interval.doubleValue() == interval.intValue()
                && interval.intValue() > 0;
    }

    static boolean matchesRequestedProfile(Map<String, Object> requested, JSONObject snapshot) {
        if (requested == null || !isCompleteSnapshot(snapshot)) return false;
        for (Map.Entry<String, Object> entry : requested.entrySet()) {
            String key = entry.getKey();
            if (!"transactionId".equals(key) && !matches(snapshot.opt(key), entry.getValue())) {
                return false;
            }
        }
        Object interval = requested.get("reportIntSecs");
        return !(interval instanceof Number) || Boolean.FALSE.equals(snapshot.opt("dynamicReporting"));
    }

    private static boolean matches(Object actual, Object expected) {
        if (actual instanceof Number && expected instanceof Number) {
            Number actualNumber = (Number) actual;
            Number expectedNumber = (Number) expected;
            return actualNumber.doubleValue() == expectedNumber.doubleValue();
        }
        return expected == null ? actual == null : expected.equals(actual);
    }

    private static void requireTransactionId(String transactionId) {
        if (!isValidTransactionId(transactionId)) {
            throw new IllegalArgumentException("Invalid settings transaction ID.");
        }
    }
}
