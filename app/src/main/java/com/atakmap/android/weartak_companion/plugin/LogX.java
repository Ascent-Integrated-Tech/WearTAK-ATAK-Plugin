package com.atakmap.android.weartak_companion.plugin;

import android.os.SystemClock;
import android.util.Log;

import java.util.Locale;

public final class LogX {
    private LogX() {}

    // Flip this to false to quiet logs without deleting them.
    public static volatile boolean ENABLED = true;

    // Use a stable prefix so Logcat filtering is easy: "WTK/"
    public static String tag(String component) {
        return "WTK/" + component;
    }

    public static void d(String tag, String msg) {
        if (!ENABLED) return;
        Log.d(tag, stamp() + msg);
    }

    public static void i(String tag, String msg) {
        if (!ENABLED) return;
        Log.i(tag, stamp() + msg);
    }

    public static void w(String tag, String msg) {
        if (!ENABLED) return;
        Log.w(tag, stamp() + msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        if (!ENABLED) return;
        Log.e(tag, stamp() + msg, t);
    }

    private static String stamp() {
        // uptime ms is ideal for timing sequences
        long ms = SystemClock.uptimeMillis();
        return String.format(Locale.US, "[t=%d] ", ms);
    }
}