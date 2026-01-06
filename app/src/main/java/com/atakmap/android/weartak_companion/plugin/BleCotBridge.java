package com.atakmap.android.weartak_companion.plugin;

import static com.atakmap.android.maps.MapView.getMapView;
import static com.atakmap.android.user.icon.Icon2525cPallet.COT_MAPPING_2525C;

import android.os.Build;
import android.util.Log;

import com.atakmap.android.chat.ChatMessageParser;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.user.PlacePointTool;
import com.atakmap.comms.CotDispatcher;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

public class BleCotBridge {

    private static final String TAG = "WTK/BleCotBridge";

    private final CotDispatcher externalCotDispatcher;
    private final CotDispatcher internalCotDispatcher;

    private MapView mapView;
    private String myUid;
    private String myCallsign;
    private Boolean watchAsSource = false;

    public BleCotBridge() {
        externalCotDispatcher = CotMapComponent.getExternalDispatcher();
        internalCotDispatcher = CotMapComponent.getParallelInternalDispatcher();
        mapView = getMapView();
        myUid = mapView.getSelfMarker().getUID();  // The uid of companion device, so all data looks like it comes from the companion plugin!
        myCallsign = mapView.getDeviceCallsign();  // cs of eud
        Log.d(TAG, "BleCotBridge initialized externalDispatcher=" + (externalCotDispatcher != null));
    }

    // =========================================================================================
    // 1) STANDARD PLI (JSON envelope input)
    // =========================================================================================
    /*public void sendStandardPli(String envelopeJson) {
        try {
            JSONObject env = new JSONObject(envelopeJson);
            JSONObject p = env.optJSONObject("payload");
            if (p == null) {
                Log.w(TAG, "sendStandardPli: missing payload");
                return;
            }

            // ----- Pull values from JSON (fall back safely) -----
            // uid: prefer payload.marker_id if present (watch marker envelope), else msg_id, else random
            String uid = optString(p, "callsign",
                    optString(env, "msg_id", UUID.randomUUID().toString()));

            // time/start/stale: prefer payload.time_start/time_stale else now/+2min
            String time = optString(p, "time_start", iso8601(System.currentTimeMillis()));
            String stale = optString(p, "time_stale", iso8601(System.currentTimeMillis() + 120000));

            // callsign/remarks/group/track: if not present, use safe defaults
            String callsign  = optString(p, "callsign", "WEAROS-TEST");
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
    }*/

    public void sendStandardPli(String envelopeJson) {
        try {
            JSONObject env = new JSONObject(envelopeJson);
            JSONObject p = env.optJSONObject("payload");
            if (p == null) {
                Log.w(TAG, "sendStandardPli: missing payload");
                return;
            }

            // ----- Identity (Of phone / companion plugin EUD) -----
            String uid = myUid;

            // time/start/stale: prefer payload.time_start/time_stale else now/+2min
            String time  = optString(p, "tStart", iso8601(System.currentTimeMillis()));
            String stale = optString(p, "tStale", iso8601(System.currentTimeMillis() + 120000));

            // callsign + optional extras
            String callsign   = myCallsign;
            String remarks    = optString(p, "remarks", "WearTAK PLI");
            String role       = optString(p, "role", "member");
            String teamValue  = optString(p, "team", "Blue");

            double courseDeg = optDouble(p, "course", 0.0);
            double speedMps  = optDouble(p, "speed", 0.0);

            // NEW: battery + HR (optional)
            // Marker payload currently uses "battery_percent"; HR key depends on your payload design.
            // We'll support "battery_percent"/"bat" and "hr"/"heart_rate"/"heartRateBpm".
            Integer batteryPct = optIntNullable(p, "battery_percent");
            if (batteryPct == null) batteryPct = optIntNullable(p, "bat");

            Integer hrBpm = optIntNullable(p, "hr");
            if (hrBpm == null) hrBpm = optIntNullable(p, "heart_rate");
            if (hrBpm == null) hrBpm = optIntNullable(p, "heartRateBpm");

            // Point: v1 uses ATAK EUD location
            EudFix fix = getEudFix();
            double lat = fix.lat, lon = fix.lon, hae = fix.hae, ce = fix.ce, le = fix.le;

            StringBuilder detail = new StringBuilder();
            detail.append("<remarks>").append(escapeXml(remarks)).append("</remarks>")
                    .append("<contact endpoint='*:-1:stcp' callsign='").append(escapeXml(callsign)).append("'/>")
                    .append("<__group role='").append(escapeXml(role)).append("' name='").append(escapeXml(teamValue)).append("'/>")
                    .append("<track course='").append(trimDouble(courseDeg)).append("' speed='").append(trimDouble(speedMps)).append("'/>");

            // Battery in <status battery=".."/>
            if (batteryPct != null) {
                int b = Math.max(0, Math.min(100, batteryPct));
                detail.append("<status battery='").append(b).append("'/>");
            }

            // HR as custom physio tag (ATAK will preserve unknown tags)
            if (hrBpm != null && hrBpm > 0) {
                detail.append("<physio hr='").append(hrBpm).append("'/>");
            }

            String xml =
                    "<?xml version='1.0' encoding='UTF-8' standalone='yes'?>"
                            + "<event version='2.0' uid='" + escapeXml(uid) + "' type='a-f-G-U-C' "
                            + "time='" + escapeXml(time) + "' start='" + escapeXml(time) + "' stale='" + escapeXml(stale) + "' how='h-g-i-g-o'>"
                            + "<point lat='" + String.format("%.6f", lat)
                            + "' lon='" + String.format("%.6f", lon)
                            + "' hae='" + String.format("%.1f", hae)
                            + "' ce='" + trimDouble(ce) + "' le='" + trimDouble(le) + "'/>"
                            + "<detail>"
                            + detail
                            + "</detail>"
                            + "</event>";

            Log.d(TAG, "Standard PLI XML:\n" + xml);

//            dispatchExternal("PLI", xml);
            dispatchCot("PLI", xml, true, true);

        } catch (Exception e) {
            Log.e(TAG, "Error in sendStandardPli(json)", e);
        }
    }

    private static String iconFromType(String type) {
        if (type == null || type.isEmpty()) return null;
        return COT_MAPPING_2525C + "/" + type.replace("-", "/");
    }

    // =========================================================================================
    // DISPATCH (internal + external)
    // =========================================================================================
    private void dispatchCot(String label, String xml, boolean injectLocal, boolean sendNetwork) {
        CotEvent evt;
        try {
            evt = CotEvent.parse(xml);
        } catch (Throwable t) {
            Log.e(TAG, "dispatchCot(" + label + "): CotEvent.parse failed", t);
            return;
        }

        if (injectLocal) {
            if (internalCotDispatcher != null) {
                try {
                    internalCotDispatcher.dispatch(evt);
                    Log.d(TAG, "dispatchCot(" + label + "): injected internal");
                } catch (Throwable t) {
                    Log.e(TAG, "dispatchCot(" + label + "): internal dispatch failed", t);
                }
            } else {
                Log.w(TAG, "dispatchCot(" + label + "): internalCotDispatcher is null");
            }
        }

        if (sendNetwork) {
            if (externalCotDispatcher != null) {
                try {
                    externalCotDispatcher.dispatch(evt);
                    Log.d(TAG, "dispatchCot(" + label + "): dispatched external");
                } catch (Throwable t) {
                    Log.e(TAG, "dispatchCot(" + label + "): external dispatch failed", t);
                }
            } else {
                Log.w(TAG, "dispatchCot(" + label + "): externalCotDispatcher is null");
            }
        }
    }

    public void sendMarkerCot(String envelopeJson) {
        try {
            JSONObject env = new JSONObject(envelopeJson);
            JSONObject p = env.optJSONObject("payload");
            if (p == null) {
                Log.w(TAG, "sendMarkerCot: missing payload");
                return;
            }

            // Marker identity + type
            String markerUid = optString(p, "uid",
                    optString(env, "msgUid", UUID.randomUUID().toString()));
            String type = optString(p, "type", "a-f-G");

            // Times: prefer payload.tStart/tStale (your marker payload uses these)
            String time  = optString(p, "tStart", iso8601(System.currentTimeMillis()));
            String start = time;
            String stale = optString(p, "tStale", iso8601(System.currentTimeMillis() + 120_000));

            // Point fields (marker payload has these)
            double lat = optDouble(p, "lat", 0.0);
            double lon = optDouble(p, "lon", 0.0);
            double hae = optDouble(p, "hae", 0.0);
            double ce  = optDouble(p, "ce", 10.0);
            double le  = optDouble(p, "le", 10.0);

            String callsign = "";
            if (watchAsSource) {
                // use watch callsign, if not, fallback to this EUD callsign.
                callsign = optString(p, "cs", myCallsign);
            } else {
                callsign = myCallsign;
            }

            Integer batteryPct = optIntNullable(p, "bat");
            if (batteryPct == null) batteryPct = optIntNullable(p, "battery_percent");

            Integer hrBpm = optIntNullable(p, "hr");
            if (hrBpm == null) hrBpm = optIntNullable(p, "heart_rate");
            if (hrBpm == null) hrBpm = optIntNullable(p, "heartRateBpm");

            // Parent/self UID for <link .../> (NOT present in your marker JSON as pasted)
            // Supported sources:
            //  - payload.parentUid / payload.linkUid (if you include it)
            //  - function parameter parentUidOverride
            String parentUid = optString(p, "parentUid", null);
            if (parentUid == null) parentUid = optString(p, "linkUid", null);
            if (parentUid == null) parentUid = myUid;

            // Build <detail> in the same structure as your expected XML
            StringBuilder detail = new StringBuilder();

            // <status readiness="true" battery=".."/>
            detail.append("<status readiness=\"true\"");
            if (batteryPct != null) {
                int b = Math.max(0, Math.min(100, batteryPct));
                detail.append(" battery='").append(b).append("'");
            }
            detail.append("/>");


            // <precisionlocation altsrc="SRTM1"/>
            detail.append("<precisionlocation altsrc=\"SRTM1\"/>");

            // <link .../> (only if parentUid is known)
            if (parentUid != null && !parentUid.isEmpty()) {
                detail.append("<link uid=\"").append(escapeXml(parentUid)).append("\" ")
                        .append("production_time=\"").append(escapeXml(time)).append("\" ")
                        .append("type=\"a-f-G-U-C\" ")
                        .append("parent_callsign=\"").append(escapeXml(callsign)).append("\" ")
                        .append("relation=\"p-p\"/>");
            }

            // <color argb="-1"/>
            detail.append("<color argb=\"-1\"/>");

            String icon = optString(p, "icn", null);

            if (icon == null || icon.isEmpty()) {
                icon = iconFromType(type);
            }

            if (icon != null && !icon.isEmpty()) {
                detail.append("<usericon iconsetpath=\"")
                        .append(icon)
                        .append("\"/>");
            }

            // <remarks>HeartRate: ...; Battery Level: ...</remarks>
            String hrText = (hrBpm == null ? "null" : String.valueOf(hrBpm));
            String batText = (batteryPct == null ? "null" : String.valueOf(batteryPct));
            String remarks = "HeartRate: " + hrText + " bpm; Battery Level: " + batText + "%";
            detail.append("<remarks>").append(escapeXml(remarks)).append("</remarks>");

            // <contact callsign="WEAROS-1075_161710Z"/>
            detail.append("<contact callsign=\"")
                    .append(escapeXml(buildMarkerCallsign(callsign, time)))
                    .append("\"/>");

            // Build final XML
            String xml =
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                            + "<event version=\"2.0\" uid=\"" + escapeXml(markerUid) + "\" type=\"" + escapeXml(type) + "\" "
                            + "time=\"" + escapeXml(time) + "\" start=\"" + escapeXml(start) + "\" stale=\"" + escapeXml(stale) + "\" "
                            + "how=\"h-g-i-g-o\" access=\"Undefined\">"
                            + "<point lat=\"" + formatDouble(lat)
                            + "\" lon=\"" + formatDouble(lon)
                            + "\" hae=\"" + formatDouble(hae)
                            + "\" ce=\"" + formatDouble(ce)
                            + "\" le=\"" + formatDouble(le) + "\"/>"
                            + "<detail>" + detail + "</detail>"
                            + "</event>";

            Log.d(TAG, "Marker CoT XML:\n" + xml);
//            dispatchExternal("MARKER", xml);
            dispatchCot("MARKER", xml, true, true);

        } catch (Exception e) {
            Log.e(TAG, "Error in sendMarkerCot(json)", e);
        }
    }

    /**
     * Creates callsign like: "WEAROS-1075_161710Z" derived from ISO8601 time.
     * Expects time like "2025-12-19T16:17:10.132Z"
     */
    private String buildMarkerCallsign(String baseCallsign, String isoTime) {
        // Minimal, safe parsing: HHmmss from positions 11..19 if format matches
        try {
            if (isoTime != null && isoTime.length() >= 19 && isoTime.charAt(10) == 'T') {
                String hh = isoTime.substring(11, 13);
                String mm = isoTime.substring(14, 16);
                String ss = isoTime.substring(17, 19);
                return baseCallsign + "_" + hh + mm + ss + "Z";
            }
        } catch (Exception ignored) {}
        return baseCallsign;
    }

    private String formatDouble(double v) {
        // Avoid Locale issues; keep full-ish precision like your expected example
        // (If you want fixed precision per field, tell me and I’ll match exactly.)
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0";
        return String.format(Locale.US, "%.7f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** Returns Integer if key exists and is parseable as int, else null. */
    private Integer optIntNullable(JSONObject o, String key) {
        if (o == null || key == null) return null;
        if (!o.has(key) || o.isNull(key)) return null;

        try {
            Object v = o.get(key);
            if (v instanceof Number) return ((Number) v).intValue();
            if (v instanceof String) {
                String s = ((String) v).trim();
                if (s.isEmpty()) return null;
                return (int) Double.parseDouble(s); // supports "87" or "87.0"
            }
        } catch (Exception ignored) {
        }
        return null;
    }



    // =========================================================================================
    // 2) EMERGENCY ALERT (JSON envelope input)
    // =========================================================================================
    /*public void sendEmergencyAlert(String envelopeJson) {
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

            String callsign  = optString(p, "callsign", "WEAROS-TEST");
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
    }*/

    public void sendEmergencyAlert(String envelopeJson) {
        try {
            JSONObject env = new JSONObject(envelopeJson);
            JSONObject p = env.optJSONObject("payload");
            if (p == null) {
                Log.w(TAG, "sendEmergencyAlert: missing payload");
                return;
            }

            String uid = optString(p, "uid",
                    optString(env, "msg_id", UUID.randomUUID().toString()));

            String time  = optString(p, "tStart", iso8601(System.currentTimeMillis()));
            String stale = optString(p, "tStale", iso8601(System.currentTimeMillis() + 300000));

            String callsign = optString(p, "callsign", "WEAROS-TEST");
            String catg     = optString(p, "catg", "Manual SOS Alert");
            String desc     = optString(p, "desc", "SOS Alert");

            int bat = optInt(p, "bat", -1);

            // Self info for link (required by Emergency ingest)
            String selfUid = optString(p, "uid", "WEAROS-UNKNOWN");
            String deviceType = mapView.getMapData().getMetaString(
                    "deviceType",
                    mapView.getContext().getString(com.atakmap.app.R.string.default_cot_type));

            // Point: use EUD location
            EudFix fix = getEudFix();

            StringBuilder detail = new StringBuilder();
            detail.append("<detail>");

            // Emergency payload
            detail.append("<emergency type='").append(escapeXml(desc)).append("'>")
                    .append(escapeXml(callsign)).append("</emergency>");

            // Contact (display)
            detail.append("<contact callsign='")
                    .append(escapeXml(callsign)).append("&#10;").append(escapeXml(catg))
                    .append("'/>");

            // REQUIRED for EmergencyAlertReceiver: link to the sender item
            detail.append("<link uid='").append(escapeXml(selfUid))
                    .append("' type='").append(escapeXml(deviceType))
                    .append("' relation='p-p'/>");

            if (bat >= 0) detail.append("<status battery='").append(bat).append("'/>");
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

            // Parse and dispatch
            com.atakmap.coremap.cot.event.CotEvent evt = com.atakmap.coremap.cot.event.CotEvent.parse(xml);

            // 1) Inject into internal pipeline so Emergency UI/marker are created
            internalCotDispatcher.dispatch(evt);

            // 2) Also send externally (network)
            externalCotDispatcher.dispatch(evt);

            // Optional fallback internal injection via import intent:
            // android.content.Intent i = new android.content.Intent(
            //         com.atakmap.android.importexport.ImportExportMapComponent.IMPORT_COT);
            // i.putExtra("event", evt);
            // com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(i);

            Log.d(TAG, "EMERGENCY ALERT SENT");
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

            String callsign  = optString(p, "callsign", "WEAROS-TEST");

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

//            dispatchExternal("EMERGENCY_CANCEL", xml);
            dispatchCot("EMERGENCY_CANCEL", xml, true, true);

        } catch (Exception e) {
            Log.e(TAG, "Error in sendEmergencyCancel(json)", e);
        }
    }


    public void sendChatViaAPI(String text) {
        // All Chat Rooms contact (broadcast)
        com.atakmap.android.contact.Contact all =
                com.atakmap.android.chat.ChatManagerMapComponent.getChatBroadcastContact();

        java.util.List<com.atakmap.android.contact.Contact> convos =
                java.util.Collections.singletonList(all);

        com.atakmap.android.chat.ChatManagerMapComponent.getInstance()
                .sendMessage(text, convos);
    }


    // =========================================================================================
// 4) CHAT (GeoChat) (JSON envelope input)
// =========================================================================================
    /*public void sendChat(String envelopeJson) {
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
            String callsign  = optString(p, "callsign", "WEAROS-TEST");

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
                    .append(" senderCallsign='").append(escapeXml(callsign)).append("'")
                    .append(" senderUid='").append(escapeXml(callsign)).append("'")
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

            //dispatchExternal("CHAT", xml);

            CotEvent chatEvent = CotEvent.parse(xml);
            ChatMessageParser chatMessageParser = new ChatMessageParser(mapView);
            chatMessageParser.parseCotEvent(chatEvent);

            externalCotDispatcher.dispatchToBroadcast(chatEvent);

            Log.d(TAG, "CHAT SENT");

            sendChatViaAPI(msg);

        } catch (Exception e) {
            Log.e(TAG, "Error in sendChat(json)", e);
        }
    }*/


    /*public void sendChat(String envelopeJson) {
        try {
            JSONObject env = new JSONObject(envelopeJson);
            JSONObject p = env.optJSONObject("payload");
            if (p == null) { Log.w(TAG, "sendChat: missing payload"); return; }

            String msgUid    = optString(p, "uid", optString(env, "msg_id", UUID.randomUUID().toString()));
            String roomUid   = optString(p, "roomUid", "WEARTAK_ROOM");
            String roomTitle = optString(p, "roomTitle", roomUid);
            String msg       = optString(p, "msg", "");
            String callsign  = optString(p, "callsign", "WEAROS-TEST");

            String time  = optString(p, "tStart", iso8601(System.currentTimeMillis()));
            String stale = optString(p, "tStale", iso8601(System.currentTimeMillis() + 600000));

            int bat = optInt(p, "bat", -1);
            int hr  = optInt(p, "hr", -1);

            // Self info
            String selfUid = mapView.getSelfMarker().getUID();
            String deviceType = mapView.getMapData().getMetaString("deviceType", "a-f");

            // Event UID must follow GeoChat pattern so Chat can parse sender/room
            String eventUid = "GeoChat." + selfUid + "." + roomUid + "." + msgUid;

            EudFix fix = getEudFix();

            StringBuilder detail = new StringBuilder();
            detail.append("<detail>");

            if (bat >= 0) detail.append("<status battery='").append(bat).append("'/>");
            if (hr >= 0)  detail.append("<sensor hr='").append(hr).append("'/>");

            // __chat block with messageId and chatgrp
            detail.append("<__chat")
                    .append(" id='").append(escapeXml(roomUid)).append("'")
                    .append(" messageId='").append(escapeXml(msgUid)).append("'")
                    .append(" chatroom='").append(escapeXml(roomTitle)).append("'")
                    .append(" senderCallsign='").append(escapeXml(callsign)).append("'")
                    .append(" groupOwner='false'>");

            // Chat group membership (at least self)
            detail.append("<chatgrp id='").append(escapeXml(roomUid)).append("'")
                    .append(" uid0='").append(escapeXml(selfUid)).append("'/>");

            detail.append("</__chat>");

            // Link back to sender
            detail.append("<link uid='").append(escapeXml(selfUid)).append("'")
                    .append(" type='").append(escapeXml(deviceType)).append("'")
                    .append(" relation='p-p'/>");

            // Message content goes in <remarks>
            detail.append("<remarks source='BAO.F.ATAK.")
                    .append(escapeXml(selfUid))
                    .append("' time='").append(escapeXml(time)).append("'>")
                    .append(escapeXml(msg))
                    .append("</remarks>");

            detail.append("</detail>");

            String xml =
                    "<?xml version='1.0' encoding='UTF-8' standalone='yes'?>"
                            + "<event version='2.0'"
                            + " uid='" + escapeXml(eventUid) + "'"
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

            CotEvent chatEvent = CotEvent.parse(xml);

            // Optional: verify Chat can parse before send (useful during dev)
            ChatMessageParser parser = new ChatMessageParser(mapView);
            parser.parseCotEvent(chatEvent);

            // Broadcast on GeoChat multicast
            externalCotDispatcher.dispatchToBroadcast(chatEvent);

            Log.d(TAG, "CHAT SENT");
        } catch (Exception e) {
            Log.e(TAG, "Error in sendChat(json)", e);
        }
    }*/


    public void sendChat(String envelopeJson) {
        try {
            JSONObject env = new JSONObject(envelopeJson);
            JSONObject p = env.optJSONObject("payload");
            if (p == null) {
                Log.w(TAG, "sendChat: missing payload");
                return;
            }

            // -------------------------------
            // Parse payload (watch envelope)
            // -------------------------------
            // Message id (used as messageId and last segment of GeoChat UID)
            String msgUid = optString(p, "uid",
                    optString(env, "msg_id", UUID.randomUUID().toString()));

            // Destination identity
            // In your watch schema: roomUid = chat room id, roomTitle = display title
            // The working WearOS XML uses:
            //   dstUid     -> __chat id / remarks to / GeoChat UID segment #3
            //   dstChatroom-> __chat chatroom / <marti><dest callsign='...'/>
            //
            // So we map:
            //   dstUid      = roomUid
            //   dstChatroom = roomTitle
            //
            String dstUid      = optString(p, "roomUid", "All Chat Rooms");
            String dstChatroom = optString(p, "roomTitle", dstUid);

            // Message text
            String chatMsg = optString(p, "msg", "");

            // Sender callsign comes from payload.cs in your earlier watch examples
            // (Your stub used "cs", not "callsign".)
            String senderCallsign = optString(p, "cs",
                    optString(p, "callsign", "WEAROS-TEST"));

            // Time/stale fields match your watch schema
            String time  = optString(p, "tStart", iso8601(System.currentTimeMillis()));
            String stale = optString(p, "tStale", iso8601(System.currentTimeMillis() + 10_000));

            // Optional diagnostics
            int bat = optInt(p, "bat", -1);
            int hr  = optInt(p, "hr", -1);

            // -------------------------------
            // Self identity (ATAK device)
            // -------------------------------
            String selfUid = myUid;
            /*try {
                if (mapView != null && mapView.getSelfMarker() != null) {
                    selfUid = mapView.getSelfMarker().getUID();
                }
            } catch (Throwable t) {
                Log.w(TAG, "sendChat: could not read self UID", t);
            }*/
            //if (selfUid == null) selfUid = "ANDROID-UNKNOWN";

            // -------------------------------
            // Build GeoChat UID (canonical)
            // GeoChat.<senderUid>.<dstUid>.<msgId>
            // -------------------------------
            String eventUid = "GeoChat." + selfUid + "." + dstUid + "." + msgUid;

            // -------------------------------
            // Point: match WearOS style (0/0 + huge errors)
            // This avoids coupling chat to GPS, and matches your working sample.
            // -------------------------------
            double lat = 0.0, lon = 0.0, hae = 999999.0, ce = 999999.0, le = 999999.0;

            // -------------------------------
            // Build <detail> exactly like WearOS
            // -------------------------------
            StringBuilder detail = new StringBuilder();
            detail.append("<detail>");

            // (Optional) keep these if you want, but WearOS chat example doesn't include them.
            // Leave them out to match sample more closely.
            // if (bat >= 0) detail.append("<status battery='").append(bat).append("'/>");
            // if (hr >= 0)  detail.append("<sensor hr='").append(hr).append("'/>");

            // __chat block (match WearOS)
            detail.append("<__chat")
                    .append(" parent='RootContactGroup'")
                    .append(" groupOwner='false'")
                    .append(" messageId='").append(escapeXml(msgUid)).append("'")
                    .append(" chatroom='").append(escapeXml(dstChatroom)).append("'")
                    .append(" id='").append(escapeXml(dstUid)).append("'")
                    .append(" senderCallsign='").append(escapeXml(senderCallsign)).append("'>");

            // chatgrp (match WearOS)
            detail.append("<chatgrp")
                    .append(" uid0='").append(escapeXml(selfUid)).append("'")
                    .append(" uid1='").append(escapeXml(dstUid)).append("'")
                    .append(" id='").append(escapeXml(dstUid)).append("'")
                    .append("/>");

            detail.append("</__chat>");

            // link back to sender PLI (match WearOS)
            detail.append("<link uid='").append(escapeXml(selfUid))
                    .append("' type='a-f-G-U-C' relation='p-p'/>");

            // remarks carries the message text (match WearOS)
            detail.append("<remarks")
                    .append(" source='").append(escapeXml(selfUid)).append("'")
                    .append(" to='").append(escapeXml(dstUid)).append("'")
                    .append(" time='").append(escapeXml(time)).append("'>")
                    .append(escapeXml(chatMsg))
                    .append("</remarks>");

            // marti routing: OMIT when sending to All Chat Rooms (match WearOS logic)
            if (!"All Chat Rooms".equalsIgnoreCase(dstUid)) {
                detail.append("<marti><dest callsign='")
                        .append(escapeXml(dstChatroom))
                        .append("'/></marti>");
            }

            detail.append("</detail>");

            // -------------------------------
            // Build full CoT
            // -------------------------------
            String xml =
                    "<?xml version='1.0' encoding='UTF-8' standalone='yes'?>"
                            + "<event version='2.0'"
                            + " uid='" + escapeXml(eventUid) + "'"
                            + " type='b-t-f'"
                            + " time='" + escapeXml(time) + "'"
                            + " start='" + escapeXml(time) + "'"
                            + " stale='" + escapeXml(stale) + "'"
                            + " how='h-g-i-g-o'"
                            + " access='Undefined'>"
                            + "<point lat='" + lat + "' lon='" + lon + "' hae='" + hae
                            + "' ce='" + ce + "' le='" + le + "'/>"
                            + detail
                            + "</event>";

            Log.d(TAG, "GeoChat XML:\n" + xml);

            CotEvent chatEvent = CotEvent.parse(xml);

            // Local verification: this should populate ATAK chat UI locally
            try {
                ChatMessageParser parser = new ChatMessageParser(mapView);
                parser.parseCotEvent(chatEvent);
                Log.d(TAG, "ChatMessageParser: parsed OK");
            } catch (Throwable t) {
                Log.w(TAG, "ChatMessageParser parse failed (still attempting send)", t);
            }

            // Network send (broadcast). Note: this can still be intercepted by other plugins.
            if (externalCotDispatcher != null) {
                externalCotDispatcher.dispatchToBroadcast(chatEvent);
                Log.d(TAG, "CHAT SENT (broadcast)");
            } else {
                Log.w(TAG, "externalCotDispatcher is null, not sending");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in sendChat(json)", e);
        }
    }

    public void handleJsonFromWearTak(String envelopeJson) {
        try {
            JSONObject env = new JSONObject(envelopeJson);

            String msgType = env.optString("msgType", "").trim();
            if (msgType.isEmpty()) {
                Log.w(TAG, "handleJsonFromWearTak: missing msg_type");
                return;
            }

            switch (msgType) {
                case "pli":
                    // Marker payload -> PLI / watch entity update
                    sendStandardPli(envelopeJson);
                    break;

                case "marker":
                    // Marker payload -> PLI / watch entity update
                    sendMarkerCot(envelopeJson);

                    break;

                case "emergency":
                    // Emergency payload includes state ALERT/CANCEL; your existing method
                    // should already choose b-a-o vs b-a-o-can based on payload.state.
                    sendEmergencyAlert(envelopeJson);
                    break;

                case "chat":
                    // GeoChat
                    sendChat(envelopeJson);
                    break;

                case "watch_info":
                    handleWatchInfo(envelopeJson);
                    break;

                default:
                    Log.w(TAG, "handleJsonFromWearTak: unknown msg_type=" + msgType);
                    break;
            }

        } catch (Exception e) {
            Log.e(TAG, "handleJsonFromWearTak: bad JSON envelope", e);
        }
    }

    private void handleWatchInfo(String json) {
        Log.i(TAG, "Watch Info Received: " + json);
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
