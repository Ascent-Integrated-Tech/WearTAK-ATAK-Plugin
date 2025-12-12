package com.atakmap.android.weartak_companion.plugin;

import android.os.Build;
import android.util.Log;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.comms.CotDispatcher;
import com.atakmap.coremap.cot.event.CotEvent;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

public class BleCotBridge {

    private static final String TAG = "BleCotBridge";

    private CotDispatcher externalCotDispatcher = CotMapComponent.getExternalDispatcher();

    public BleCotBridge() {
        Log.d(TAG, "BleCotBridge initialized");
    }

    // -----------------------------------------------------------------------------------------
    // STANDARD PLI
    // -----------------------------------------------------------------------------------------
    public void sendStandardPli(
            String uid,
            double lat,
            double lon,
            double hae,
            double ce,
            double le,
            String remarks,
            String callsign,
            String role,
            String teamValue,
            double courseDeg,
            double speedMps,
            String batdokDetailXml,
            String hailCotDetailXml
    ) {
        try {
            Log.d(TAG, "sendStandardPli() called with:"
                    + "\n  uid=" + uid
                    + "\n  lat=" + lat
                    + "\n  lon=" + lon
                    + "\n  hae=" + hae
                    + "\n  ce=" + ce
                    + "\n  le=" + le
                    + "\n  remarks=" + remarks
                    + "\n  callsign=" + callsign
                    + "\n  role=" + role
                    + "\n  teamValue=" + teamValue
                    + "\n  courseDeg=" + courseDeg
                    + "\n  speedMps=" + speedMps
                    + "\n  batdokDetailXml=" + batdokDetailXml
                    + "\n  hailCotDetailXml=" + hailCotDetailXml);

            long now = System.currentTimeMillis();
            String time = iso8601(now);
            String stale = iso8601(now + 120000);  // 2 minutes stale time

            String ceTrim = trimDouble(ce);
            String leTrim = trimDouble(le);
            String courseTrim = trimDouble(courseDeg);
            String speedTrim = trimDouble(speedMps);

            String batdokStr = batdokDetailXml != null ? batdokDetailXml : "";
            String hailStr = hailCotDetailXml != null ? hailCotDetailXml : "";

            String xml =
                    "<?xml version='1.0' encoding='UTF-8' standalone='yes'?>"
                            + "<event version='2.0' uid='" + uid + "' type='a-f-G-U-C' "
                            + "time='" + time + "' start='" + time + "' stale='" + stale + "' how='m-g'>"
                            + "<point lat='" + String.format("%.6f", lat)
                            + "' lon='" + String.format("%.6f", lon)
                            + "' hae='" + String.format("%.1f", hae)
                            + "' ce='" + ceTrim + "' le='" + leTrim + "'/>"
                            + "<detail>"
                            + "<remarks>" + remarks + "</remarks>"
                            + batdokStr
                            + "<contact endpoint='*:-1:stcp' callsign='" + callsign + "'/>"
                            + "<__group role='" + role + "' name='" + teamValue + "'/>"
                            + "<track course='" + courseTrim + "' speed='" + speedTrim + "'/>"
                            + hailStr
                            + "</detail>"
                            + "</event>";

            Log.d(TAG, "Standard PLI XML:\n" + xml);

            sendCotXmlToTak(xml);

            externalCotDispatcher.dispatch(CotEvent.parse(xml));
            Log.d(TAG, "SENT Standard PLI XML");

        } catch (Exception e) {
            Log.e(TAG, "Error in sendStandardPli()", e);
        }
    }

    // -----------------------------------------------------------------------------------------
    // EMERGENCY ALERT
    // -----------------------------------------------------------------------------------------
    public void sendEmergencyAlert(
            String uid,
            double lat,
            double lon,
            double hae,
            double ce,
            double le,
            String callsign,
            String emergencyCallsign,
            String emergencyCategory,
            String emergencyDesc,
            String linkUid
    ) {
        try {
            Log.d(TAG, "sendEmergencyAlert() called with:"
                    + "\n  uid=" + uid
                    + "\n  lat=" + lat
                    + "\n  lon=" + lon
                    + "\n  hae=" + hae
                    + "\n  ce=" + ce
                    + "\n  le=" + le
                    + "\n  callsign=" + callsign
                    + "\n  emergencyCallsign=" + emergencyCallsign
                    + "\n  emergencyCategory=" + emergencyCategory
                    + "\n  emergencyDesc=" + emergencyDesc
                    + "\n  linkUid=" + linkUid);

            long now = System.currentTimeMillis();
            String time = iso8601(now);
            String stale = iso8601(now + 300000);  // 5 minutes

            String ceTrim = trimDouble(ce);
            String leTrim = trimDouble(le);

            String xml =
                    "<?xml version='1.0' encoding='UTF-8' standalone='yes'?>"
                            + "<event version='2.0' uid='" + uid + "' type='b-a-o' "
                            + "time='" + time + "' start='" + time + "' stale='" + stale
                            + "' how='h-e' access='Undefined'>"
                            + "<point lat='" + lat + "' lon='" + lon + "' hae='" + hae
                            + "' ce='" + ceTrim + "' le='" + leTrim + "'/>"
                            + "<detail>"
                            + "<link uid='" + linkUid + "' type='a-f-G-U-C' relation='p-p'/>"
                            + "<contact callsign='" + emergencyCallsign + "&#10;" + emergencyCategory + "'/>"
                            + "<emergency type='" + emergencyDesc + "'>" + callsign + "</emergency>"
                            + "<usericon iconsetpath='911 Alert'/>"
                            + "<color argb='-1'/>"
                            + "</detail>"
                            + "</event>";

            Log.d(TAG, "Emergency ALERT XML:\n" + xml);

            sendCotXmlToTak(xml);
            externalCotDispatcher.dispatch(CotEvent.parse(xml));
            Log.d(TAG, "SENT Emergency XML");

        } catch (Exception e) {
            Log.e(TAG, "Error in sendEmergencyAlert()", e);
        }
    }

    // -----------------------------------------------------------------------------------------
    // EMERGENCY CANCEL
    // -----------------------------------------------------------------------------------------
    public void sendEmergencyCancel(
            String uid,
            double lat,
            double lon,
            double hae,
            double ce,
            double le,
            String callsign
    ) {
        try {
            Log.d(TAG, "sendEmergencyCancel() called with:"
                    + "\n  uid=" + uid
                    + "\n  lat=" + lat
                    + "\n  lon=" + lon
                    + "\n  hae=" + hae
                    + "\n  ce=" + ce
                    + "\n  le=" + le
                    + "\n  callsign=" + callsign);

            long now = System.currentTimeMillis();
            String time = iso8601(now);
            String stale = iso8601(now + 300000);

            String ceTrim = trimDouble(ce);
            String leTrim = trimDouble(le);

            String xml =
                    "<?xml version='1.0' encoding='UTF-8' standalone='yes'?>"
                            + "<event version='2.0' uid='" + uid + "' type='b-a-o-can' "
                            + "time='" + time + "' start='" + time + "' stale='" + stale
                            + "' how='h-e' access='Undefined'>"
                            + "<point lat='" + lat + "' lon='" + lon + "' hae='" + hae
                            + "' ce='" + ceTrim + "' le='" + leTrim + "'/>"
                            + "<detail>"
                            + "<emergency cancel='true'>" + callsign + "</emergency>"
                            + "</detail>"
                            + "</event>";

            Log.d(TAG, "Emergency CANCEL XML:\n" + xml);

            sendCotXmlToTak(xml);

            externalCotDispatcher.dispatch(CotEvent.parse(xml));
            Log.d(TAG, "Canceled Emergency XML");

        } catch (Exception e) {
            Log.e(TAG, "Error in sendEmergencyCancel()", e);
        }
    }

    // -----------------------------------------------------------------------------------------
    // SEND TO ATAK COT BUS
    // -----------------------------------------------------------------------------------------
    private void sendCotXmlToTak(String xml) {
        Log.d(TAG, "sendCotXmlToTak(): injecting CoT into ATAK (mock)");
        Log.d(TAG, "COT PAYLOAD:\n" + xml);

        // TODO: replace with actual ICotService injection
        // cotService.injectCot(xml);

        Log.d(TAG, "sendCotXmlToTak(): DONE (mocked)");
    }

    // -----------------------------------------------------------------------------------------
    // UTIL HELPERS
    // -----------------------------------------------------------------------------------------
    private static String trimDouble(double d) {
        return ((int) d) == d ? Integer.toString((int) d) : Double.toString(d);
    }

    private static String iso8601(long timeMs) {
        java.time.Instant i = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            i = Instant.ofEpochMilli(timeMs);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return DateTimeFormatter.ISO_INSTANT.format(i);
        }

        return "False";
    }
}
