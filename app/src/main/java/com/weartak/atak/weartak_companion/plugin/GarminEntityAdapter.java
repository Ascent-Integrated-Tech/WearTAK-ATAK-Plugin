package com.weartak.atak.weartak_companion.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Normalizes ATAK entities to the Garmin phone-relay dictionary contract. */
final class GarminEntityAdapter {
    private GarminEntityAdapter() { }

    static Map<String, Object> normalize(String uid, double lat, double lon, String type,
            Map<String, Object> metadata, boolean self, boolean watchOrigin) {
        if (self || watchOrigin || uid == null || uid.trim().isEmpty()
                || !supportedType(type)) return null;
        if (metadata == null) metadata = Collections.emptyMap();
        boolean alert = isAlert(type, metadata);
        boolean cancelled = type.endsWith("-can")
                || "CANCEL".equalsIgnoreCase(text(metadata, "state"))
                || "CANCEL".equalsIgnoreCase(text(metadata, "alertState"))
                || Boolean.FALSE.equals(metadata.get("active"))
                || Boolean.TRUE.equals(metadata.get("cancel"));
        boolean location = PhoneFixValidator.isUsable(lat, lon);
        if (!alert && (!location || expired(text(metadata, "stale")))) return null;
        Map<String, Object> entity = new HashMap<>();
        entity.put("uid", uid);
        entity.put("type", type);
        if ("b-m-p-s-p-i".equals(type)) entity.put("isPoint", true);
        if (location) {
            entity.put("lat", lat);
            entity.put("lon", lon);
        }
        for (String key : new String[]{"callSign", "callsign", "team", "role", "how",
                "time", "start", "stale", "senderUID"}) {
            String value = text(metadata, key);
            if (!value.isEmpty()) entity.put(key, value);
        }
        if (alert) {
            entity.put("isAlert", true);
            entity.put("active", !cancelled);
            entity.put("alert", !cancelled);
            Map<String, Object> emergency = new HashMap<>();
            emergency.put("active", !cancelled);
            if (cancelled) {
                entity.put("state", "CANCEL");
                emergency.put("cancel", true);
            }
            String category = text(metadata, "category");
            if (!category.isEmpty()) {
                entity.put("category", category);
                emergency.put("category", category);
            }
            entity.put("emergency", emergency);
        }
        return entity;
    }

    static boolean supportedType(String type) {
        return type != null && (type.startsWith("a-") || type.startsWith("b-a-o")
                || "b-a-g".equals(type) || "b-m-p-s-p-i".equals(type));
    }

    static boolean expired(String stale) {
        if (stale == null || stale.isEmpty()) return false;
        try {
            return java.time.Instant.parse(stale).toEpochMilli() <= System.currentTimeMillis();
        } catch (java.time.format.DateTimeParseException e) {
            return true;
        }
    }

    static boolean isAlert(String type, Map<String, Object> metadata) {
        return type != null && (type.startsWith("b-a-o") || "b-a-g".equals(type)
                || Boolean.TRUE.equals(metadata.get("isAlert")));
    }

    static List<Map<String, Object>> nearest(List<Map<String, Object>> candidates,
            double selfLat, double selfLon, int requestedLimit) {
        if (!PhoneFixValidator.isUsable(selfLat, selfLon)) return Collections.emptyList();
        int limit = Math.max(1, Math.min(requestedLimit, 50));
        List<Map<String, Object>> valid = new ArrayList<>();
        for (Map<String, Object> entity : candidates) {
            Object lat = entity.get("lat"), lon = entity.get("lon");
            boolean located = lat instanceof Number && lon instanceof Number
                    && PhoneFixValidator.isUsable(((Number) lat).doubleValue(),
                            ((Number) lon).doubleValue());
            if ((located || Boolean.TRUE.equals(entity.get("isAlert")))
                    && !Boolean.FALSE.equals(entity.get("active"))) valid.add(entity);
        }
        Collections.sort(valid, (a, b) -> {
            int order = Double.compare(distance(selfLat, selfLon, a), distance(selfLat, selfLon, b));
            return order != 0 ? order : ((String) a.get("uid")).compareTo((String) b.get("uid"));
        });
        return new ArrayList<>(valid.subList(0, Math.min(limit, valid.size())));
    }

    private static double distance(double lat, double lon, Map<String, Object> entity) {
        if (!(entity.get("lat") instanceof Number) || !(entity.get("lon") instanceof Number))
            return Double.POSITIVE_INFINITY;
        double otherLat = ((Number) entity.get("lat")).doubleValue();
        double otherLon = ((Number) entity.get("lon")).doubleValue();
        double dLat = Math.toRadians(otherLat - lat), dLon = Math.toRadians(otherLon - lon);
        double a = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(Math.toRadians(lat))
                * Math.cos(Math.toRadians(otherLat)) * Math.pow(Math.sin(dLon / 2), 2);
        return Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1 - a)));
    }

    private static String text(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value instanceof String ? ((String) value).trim() : "";
    }
}
