package com.atakmap.android.weartak_companion.plugin;

import android.os.Build;
import android.util.Log;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.comms.CotDispatcher;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import org.json.JSONObject;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class BleCotBridge {

    private static final String TAG = "BleCotBridge";

    private final CotDispatcher externalCotDispatcher;

    private MapView mapView;

    public BleCotBridge() {
        externalCotDispatcher = CotMapComponent.getExternalDispatcher();
        mapView = MapView.getMapView();
        Log.d(TAG, "BleCotBridge initialized externalDispatcher=" + (externalCotDispatcher != null));
    }

    // =========================================================================================
    // 1) STANDARD PLI (JSON envelope input)
    // =========================================================================================
    public void sendStandardPli(String envelopeJson) {
        try {
            JSONObject env = new JSONObject(envelopeJson);
            JSONObject p = env.optJSONObject("payload");
            if (p == null) {
                Log.w(TAG, "sendStandardPli: missing payload");
                return;
            }

            // ----- Pull values from JSON (fall back safely) -----
            // uid: prefer payload.marker_id if present (watch marker envelope), else msg_id, else random
            String uid = optString(p, "marker_id",
                    optString(env, "msg_id", UUID.randomUUID().toString()));

            // time/start/stale: prefer payload.time_start/time_stale else now/+2min
            String time = optString(p, "time_start", iso8601(System.currentTimeMillis()));
            String stale = optString(p, "time_stale", iso8601(System.currentTimeMillis() + 120000));

            // callsign/remarks/group/track: if not present, use safe defaults
            String callsign = mapView.getDeviceCallsign();
            String remarks  = optString(p, "remarks", "WearTAK PLI");
            String role     = optString(p, "role", "member");
            String teamValue= optString(p, "team", "Blue");

            double courseDeg = optDouble(p, "course", 0.0);
            double speedMps  = optDouble(p, "speed", 0.0);

            // Point: v1 you want ATAK EUD location, not payload lat/lon
            EudFix fix = getEudFix();
            double lat = fix.lat, lon = fix.lon, hae = fix.hae, ce = fix.ce, le = fix.le;

            String xml =
                    "<?xml version='1.0' encoding='UTF-8' standalone='yes'?>"
                            + "<event version='2.0' uid='" + escapeXml(uid) + "' type='a-f-G-U-C' "
                            + "time='" + escapeXml(time) + "' start='" + escapeXml(time) + "' stale='" + escapeXml(stale) + "' how='m-g'>"
                            + "<point lat='" + String.format("%.6f", lat)
                            + "' lon='" + String.format("%.6f", lon)
                            + "' hae='" + String.format("%.1f", hae)
                            + "' ce='" + trimDouble(ce) + "' le='" + trimDouble(le) + "'/>"
                            + "<detail>"
                            + "<remarks>" + escapeXml(remarks) + "</remarks>"
                            + "<contact endpoint='*:-1:stcp' callsign='" + escapeXml(callsign) + "'/>"
                            + "<__group role='" + escapeXml(role) + "' name='" + escapeXml(teamValue) + "'/>"
                            + "<track course='" + trimDouble(courseDeg) + "' speed='" + trimDouble(speedMps) + "'/>"
                            + "</detail>"
                            + "</event>";

            Log.d(TAG, "Standard PLI XML:\n" + xml);

            dispatchExternal("PLI", xml);

        } catch (Exception e) {
            Log.e(TAG, "Error in sendStandardPli(json)", e);
        }
    }

    // =========================================================================================
    // 2) EMERGENCY ALERT (JSON envelope input)
    // =========================================================================================
    public void sendEmergencyAlert(String envelopeJson) {
        try {
            JSONObject env = new JSONObject(envelopeJson);
            JSONObject p = env.optJSONObject("payload");
            if (p == null) {
                Log.w(TAG, "sendEmergencyAlert: missing payload");
                return;
            }

            // p.uid preferred (your emergency payload), else msg_id
            String uid = optString(p, "uid",
                    optString(env, "msg_id", UUID.randomUUID().toString()));

            String time  = optString(p, "tStart", iso8601(System.currentTimeMillis()));
            String stale = optString(p, "tStale", iso8601(System.currentTimeMillis() + 300000));

            String callsign = mapView.getDeviceCallsign();
            String catg     = optString(p, "catg", "Manual SOS Alert");
            String desc     = optString(p, "desc", "SOS Alert");

            int bat = optInt(p, "bat", -1);

            // Point: use EUD location for now
            EudFix fix = getEudFix();

            StringBuilder detail = new StringBuilder();
            detail.append("<detail>");
            detail.append("<emergency type='").append(escapeXml(desc)).append("'>")
                    .append(escapeXml(callsign)).append("</emergency>");
            detail.append("<contact callsign='")
                    .append(escapeXml(callsign)).append("&#10;").append(escapeXml(catg))
                    .append("'/>");
            if (bat >= 0) {
                detail.append("<status battery='").append(bat).append("'/>");
            }
            detail.append("<usericon iconsetpath='911 Alert'/>");
            detail.append("<color argb='-1'/>");
            detail.append("</detail>");

            String xml =
                    "<?xml version='1.0' encoding='UTF-8' standalone='yes'?>"
                            + "<event version='2.0' uid='" + escapeXml(uid) + "' type='b-a-o' "
                            + "time='" + escapeXml(time) + "' start='" + escapeXml(time) + "' stale='" + escapeXml(stale)
                            + "' how='h-e' access='Undefined'>"
                            + "<point lat='" + fix.lat + "' lon='" + fix.lon + "' hae='" + fix.hae
                            + "' ce='" + trimDouble(fix.ce) + "' le='" + trimDouble(fix.le) + "'/>"
                            + detail
                            + "</event>";

            Log.d(TAG, "Emergency ALERT XML:\n" + xml);

            dispatchExternal("EMERGENCY_ALERT", xml);

        } catch (Exception e) {
            Log.e(TAG, "Error in sendEmergencyAlert(json)", e);
        }
    }

    // =========================================================================================
    // 3) EMERGENCY CANCEL (JSON envelope input)
    // =========================================================================================
    public void sendEmergencyCancel(String envelopeJson) {
        try {
            JSONObject env = new JSONObject(envelopeJson);
            JSONObject p = env.optJSONObject("payload");
            if (p == null) {
                Log.w(TAG, "sendEmergencyCancel: missing payload");
                return;
            }

            String uid = optString(p, "uid",
                    optString(env, "msg_id", UUID.randomUUID().toString()));

            String time  = optString(p, "tStart", iso8601(System.currentTimeMillis()));
            String stale = optString(p, "tStale", iso8601(System.currentTimeMillis() + 300000));

            String callsign = mapView.getDeviceCallsign();

            EudFix fix = getEudFix();

            String xml =
                    "<?xml version='1.0' encoding='UTF-8' standalone='yes'?>"
                            + "<event version='2.0' uid='" + escapeXml(uid) + "' type='b-a-o-can' "
                            + "time='" + escapeXml(time) + "' start='" + escapeXml(time) + "' stale='" + escapeXml(stale)
                            + "' how='h-e' access='Undefined'>"
                            + "<point lat='" + fix.lat + "' lon='" + fix.lon + "' hae='" + fix.hae
                            + "' ce='" + trimDouble(fix.ce) + "' le='" + trimDouble(fix.le) + "'/>"
                            + "<detail>"
                            + "<emergency cancel='true'>" + escapeXml(callsign) + "</emergency>"
                            + "</detail>"
                            + "</event>";

            Log.d(TAG, "Emergency CANCEL XML:\n" + xml);

            dispatchExternal("EMERGENCY_CANCEL", xml);

        } catch (Exception e) {
            Log.e(TAG, "Error in sendEmergencyCancel(json)", e);
        }
    }

    // =========================================================================================
// 4) CHAT (GeoChat) (JSON envelope input)
// =========================================================================================
    public void sendChat(String envelopeJson) {
        try {
            JSONObject env = new JSONObject(envelopeJson);
            JSONObject p = env.optJSONObject("payload");
            if (p == null) {
                Log.w(TAG, "sendChat: missing payload");
                return;
            }

            // Payload fields from your Kotlin builder
            String msgUid    = optString(p, "uid", optString(env, "msg_id", UUID.randomUUID().toString()));
            String roomUid   = optString(p, "roomUid", "WEARTAK_ROOM");
            String roomTitle = optString(p, "roomTitle", roomUid);
            String msg       = optString(p, "msg", "");
            String cs        = optString(p, "cs", "WearTAK");

            String time  = optString(p, "tStart", iso8601(System.currentTimeMillis()));
            String stale = optString(p, "tStale", iso8601(System.currentTimeMillis() + 600000)); // +10 min

            int bat = optInt(p, "bat", -1);
            int hr  = optInt(p, "hr", -1);

            // CoT uid choice: stable per-room, or per-message.
            // For v1, simplest is: use payload.uid (message id) to avoid collisions.
            String uid = msgUid;

            // GeoChat point is commonly 0/0; we can also put EUD location.
            // To be safe for ATAK, we'll use EUD location.
            EudFix fix = getEudFix();

            // Conservative GeoChat detail format
            // NOTE: Different ATAK builds sometimes expect slightly different attributes;
            // this is a solid starting point that usually shows up in Chat.
            StringBuilder detail = new StringBuilder();
            detail.append("<detail>");

            // Optional status info from watch
            if (bat >= 0) detail.append("<status battery='").append(bat).append("'/>");
            if (hr >= 0)  detail.append("<sensor hr='").append(hr).append("'/>");

            // The chat block
            detail.append("<__chat")
                    .append(" id='").append(escapeXml(roomUid)).append("'")
                    .append(" chatroom='").append(escapeXml(roomTitle)).append("'")
                    .append(" senderCallsign='").append(escapeXml(cs)).append("'")
                    .append(" senderUid='").append(escapeXml(cs)).append("'")
                    .append(" groupOwner='false'")
                    .append(">");
            detail.append(escapeXml(msg));
            detail.append("</__chat>");

            detail.append("</detail>");

            String xml =
                    "<?xml version='1.0' encoding='UTF-8' standalone='yes'?>"
                            + "<event version='2.0'"
                            + " uid='" + escapeXml(uid) + "'"
                            + " type='b-t-f'"
                            + " time='" + escapeXml(time) + "'"
                            + " start='" + escapeXml(time) + "'"
                            + " stale='" + escapeXml(stale) + "'"
                            + " how='h-g-i-g-o'>"
                            + "<point lat='" + fix.lat + "' lon='" + fix.lon + "' hae='" + fix.hae
                            + "' ce='" + trimDouble(fix.ce) + "' le='" + trimDouble(fix.le) + "'/>"
                            + detail
                            + "</event>";

            Log.d(TAG, "GeoChat XML:\n" + xml);

            dispatchExternal("CHAT", xml);

        } catch (Exception e) {
            Log.e(TAG, "Error in sendChat(json)", e);
        }
    }


    // =========================================================================================
    // DISPATCH (external)
    // =========================================================================================
    private void dispatchExternal(String label, String xml) {
        if (externalCotDispatcher == null) {
            Log.w(TAG, "dispatchExternal(" + label + "): externalCotDispatcher is null");
            return;
        }

        Log.d(TAG, "dispatchExternal(" + label + "): dispatching CotEvent.parse(xml)");
        Log.d(TAG, "COT PAYLOAD:\n" + xml);

        externalCotDispatcher.dispatch(CotEvent.parse(xml));

        Log.d(TAG, "dispatchExternal(" + label + "): SENT");
    }

    // =========================================================================================
    // EUD LOCATION HELPERS
    // =========================================================================================

    /**
     * Gets current ATAK self marker point; returns safe defaults if unavailable.
     * You can later improve CE/LE extraction if you find the right metadata keys in your build.
     */
    private EudFix getEudFix() {
        try {
            MapView mv = mapView;

            if (mv == null) {
                Log.w(TAG, "getEudFix: MapView null");
                return EudFix.defaultFix();
            }
            Marker sm = mv.getSelfMarker();
            if (sm == null) {
                Log.w(TAG, "getEudFix: SelfMarker null");
                return EudFix.defaultFix();
            }

            GeoPoint gp = sm.getPoint();
            if (gp == null) {
                Log.w(TAG, "getEudFix: GeoPoint null");
                return EudFix.defaultFix();
            }

            double lat = gp.getLatitude();
            double lon = gp.getLongitude();
            double hae = gp.getAltitude();

            // Default CE/LE large if we don't have metadata
            double ce = 10.0;
            double le = 10.0;

            return new EudFix(lat, lon, hae, ce, le);

        } catch (Throwable t) {
            Log.e(TAG, "getEudFix: exception", t);
            return EudFix.defaultFix();
        }
    }

    private static class EudFix {
        final double lat, lon, hae, ce, le;
        EudFix(double lat, double lon, double hae, double ce, double le) {
            this.lat = lat; this.lon = lon; this.hae = hae; this.ce = ce; this.le = le;
        }
        static EudFix defaultFix() {
            return new EudFix(0.0, 0.0, 0.0, 9999999.0, 9999999.0);
        }
    }

    // =========================================================================================
    // JSON HELPERS (lenient)
    // =========================================================================================
    private static String optString(JSONObject o, String k, String def) {
        String v = o.optString(k, null);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static int optInt(JSONObject o, String k, int def) {
        if (!o.has(k)) return def;
        return o.optInt(k, def);
    }

    private static double optDouble(JSONObject o, String k, double def) {
        if (!o.has(k)) return def;
        return o.optDouble(k, def);
    }

    // =========================================================================================
    // UTIL HELPERS
    // =========================================================================================
    private static String trimDouble(double d) {
        return ((int) d) == d ? Integer.toString((int) d) : Double.toString(d);
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String iso8601(long timeMs) {
        // Your build is Java 8 language level, but Android device runtime supports java.time on modern builds.
        // ATAK CIV devices are typically modern enough. If not, we can replace with SimpleDateFormat UTC.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(timeMs));
        } else {
            // fallback: very rough; replace if you care about pre-O devices
            return String.valueOf(timeMs);
        }
    }
}
