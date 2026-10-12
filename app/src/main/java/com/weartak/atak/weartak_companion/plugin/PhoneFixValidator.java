package com.weartak.atak.weartak_companion.plugin;

/** Decides whether a phone location is real enough to place an emergency event at. */
final class PhoneFixValidator {

    private PhoneFixValidator() {}

    static boolean isUsable(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) return false;
        if (Double.isInfinite(lat) || Double.isInfinite(lon)) return false;
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return false;
        // 0,0 is ATAK's "no fix" placeholder, not a real position.
        return !(lat == 0.0 && lon == 0.0);
    }
}
