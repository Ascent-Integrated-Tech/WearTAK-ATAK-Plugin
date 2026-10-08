package com.weartak.atak.weartak_companion.plugin;

import static com.atakmap.android.maps.MapView.getMapView;
import static com.atakmap.android.user.icon.Icon2525cPallet.COT_MAPPING_2525C;

import android.os.Build;
import android.util.Log;

import com.atakmap.android.chat.ChatMessageParser;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.comms.CotDispatcher;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;
import com.atakmap.coremap.maps.coords.GeoPoint;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BleCotBridge {

    private static final String TAG = "WTK/BleCotBridge";

    private final CotDispatcher externalCotDispatcher;
    private final CotDispatcher internalCotDispatcher;

    private MapView mapView;
    private String myUid;
    private String pluginCallsign;
    private Boolean watchAsSource = false;

    public BleCotBridge() {
        externalCotDispatcher = CotMapComponent.getExternalDispatcher();
        internalCotDispatcher = CotMapComponent.getParallelInternalDispatcher();
        mapView = getMapView();
        myUid = mapView.getSelfMarker().getUID();  // The uid of companion device, so all data looks like it comes from the companion plugin!
        pluginCallsign = mapView.getDeviceCallsign();  // cs of eud
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

            // ----- Identity (Of phone / companion plugin EUD) -----
            String uid = myUid;

            // time/start/stale: prefer payload.time_start/time_stale else now/+2min
            String time  = optString(p, "tStart", iso8601(System.currentTimeMillis()));
            String stale = optString(p, "tStale", iso8601(System.currentTimeMillis() + 120000));

            // callsign + optional extras
            String callsign   = pluginCallsign;
            String remarks    = optString(p, "remarks", "WearTAK PLI");
            String role       = optString(p, "role", "member");
            String teamValue  = optString(p, "team", "Blue");

            double courseDeg = optDouble(p, "course", 0.0);
            double speedMps  = optDouble(p, "speed", 0.0);

            // battery and hr in details
            Integer batteryPct = optIntNullable(p, "battery_percent");
            if (batteryPct == null) batteryPct = optIntNullable(p, "bat");

            Integer hrBpm = optIntNullable(p, "hr");
            if (hrBpm == null) hrBpm = optIntNullable(p, "heart_rate");
            if (hrBpm == null) hrBpm = optIntNullable(p, "heartRateBpm");

            // Point: force ATAK EUD location
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

            dispatchCot("PLI", xml);

        } catch (Exception e) {
            Log.e(TAG, "Error in sendStandardPli(json)", e);
        }
    }

    // =========================================================================================
    // DISPATCH (internal + external)
    // =========================================================================================
    private void dispatchCot(String label, String xml) {
        if (xml == null || xml.isEmpty()) {
            Log.w(TAG, "dispatchCot(" + label + "): empty xml");
            return;
        }

        final CotEvent evt;
        try {
            evt = CotEvent.parse(xml);
        } catch (Throwable t) {
            Log.e(TAG, "dispatchCot(" + label + "): parse failed", t);
            return;
        }

        // Internal
        if (internalCotDispatcher != null) {
            try {
                internalCotDispatcher.dispatch(evt);
                Log.d(TAG, "dispatchCot(" + label + "): dispatched INTERNAL");
            } catch (Throwable t) {
                Log.e(TAG, "dispatchCot(" + label + "): internal dispatch failed", t);
            }
        }

        // External
        if (externalCotDispatcher != null) {
            try {
                externalCotDispatcher.dispatch(evt);
                Log.d(TAG, "dispatchCot(" + label + "): dispatched EXTERNAL");
            } catch (Throwable t) {
                Log.e(TAG, "dispatchCot(" + label + "): external dispatch failed", t);
            }
        }

        // One-line summary
        Log.i(TAG, "dispatchCot(" + label + "): done.)");
    }

    public void sendMarkerCot(String envelopeJson) {
        sendMarkerCot(envelopeJson, false);
    }

    private void sendMarkerCot(String envelopeJson, boolean usePayloadLocation) {
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
            String title = optString(p, "title", "");

            // Point fields (marker payload has these)
            EudFix eudFix = getEudFix();

            double lat = eudFix.lat;
            double lon = eudFix.lon;
            double hae = eudFix.hae;
            double ce  = eudFix.ce;
            double le  = eudFix.le;

            String callsign = pluginCallsign;
            if (watchAsSource || usePayloadLocation) {
                // use watch callsign & location fix, fallback to this device on null
                callsign = optString(p, "cs", pluginCallsign);
                lat = optDouble(p, "lat", lat);
                lon = optDouble(p, "lon", lon);
                hae = optDouble(p, "hae", hae);
                ce  = optDouble(p, "ce", ce);
                le  = optDouble(p, "le", le);
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
            if (null == title || title.isEmpty()) {
                title = escapeXml(buildMarkerCallsign(callsign, time));
            }
            // <contact callsign="WEAROS-1075_161710Z"/>
            detail.append("<contact callsign=\"")
                    .append(title)
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

            dispatchCot("MARKER", xml);

        } catch (Exception e) {
            Log.e(TAG, "Error in sendMarkerCot(json)", e);
        }
    }

    // =========================================================================================
    // 2) EMERGENCY ALERT (JSON envelope input)
    //         a) handleEmergencyCot() routes json string to alert or cancel builder
    //         b) sendEmergencyAlert() builds the Alert cot xml event & sends it both externally & internally
    //         b) sendEmergencyCancel() builds the Cancel Alert cot xml event & sends it both externally & internally
    // =========================================================================================
    private void handleEmergencyCot(String envelopeJson) {
        handleEmergencyCot(envelopeJson, null);
    }

    /** @param fixOverride location to use for the event, or null to use {@link #getEudFix()}. */
    private void handleEmergencyCot(String envelopeJson, EudFix fixOverride) {
        try {
            JSONObject env = new JSONObject(envelopeJson);
            JSONObject p = env.optJSONObject("payload");
            if (p == null) {
                Log.w(TAG, "handleEmergencyCot: missing payload");
                return;
            }

            String state = optString(p, "state", "");

            boolean isAlert  = "ALERT".equalsIgnoreCase(state);
            boolean isCancel = "CANCEL".equalsIgnoreCase(state);

            if (isAlert) {
                sendEmergencyAlert(envelopeJson, fixOverride);
            }
            if (isCancel) {
                sendEmergencyCancel(envelopeJson, fixOverride);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in sendEmergencyAlert(json)", e);
        }
    }

    public void sendEmergencyAlert(String envelopeJson) {
        sendEmergencyAlert(envelopeJson, null);
    }

    private void sendEmergencyAlert(String envelopeJson, EudFix fixOverride) {
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

            String callsign = "";
            if (watchAsSource) {
                // use watch callsign, if not, fallback to this EUD callsign.
                callsign = optString(p, "cs", pluginCallsign);
            } else {
                callsign = pluginCallsign;
            }

            String alertType = optString(p, "alertType", null);
            String catg = optString(p, "catg",
                    alertType == null ? "Manual SOS Alert" : alertType);
            String desc = optString(p, "desc",
                    alertType == null ? "SOS Alert" : alertType);

            int bat = optInt(p, "bat", -1);

            // Self info for link (required by Emergency ingest)
            String selfUid = optString(p, "uid", "WEAROS-UNKNOWN");
            String deviceType = mapView.getMapData().getMetaString(
                    "deviceType",
                    mapView.getContext().getString(com.atakmap.app.R.string.default_cot_type));

            // Point: use EUD location
            EudFix fix = (fixOverride != null) ? fixOverride : getEudFix();

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

            dispatchCot("Emergency ALERT", xml);

        } catch (Exception e) {
            Log.e(TAG, "Error in sendEmergencyAlert(json)", e);
        }
    }

    public void sendEmergencyCancel(String envelopeJson) {
        sendEmergencyCancel(envelopeJson, null);
    }

    private void sendEmergencyCancel(String envelopeJson, EudFix fixOverride) {
        try {
            JSONObject env = new JSONObject(envelopeJson);
            JSONObject p = env.optJSONObject("payload");
            if (p == null) {
                Log.w(TAG, "sendEmergencyCancel: missing payload");
                return;
            }

            String uid = optString(p, "uid", optString(env, "msg_id", UUID.randomUUID().toString()));

            String time  = optString(p, "tStart", iso8601(System.currentTimeMillis()));
            String stale = optString(p, "tStale", iso8601(System.currentTimeMillis() + 300000));

            String callsign  = optString(p, "callsign", "WEAROS-TEST");

            EudFix fix = (fixOverride != null) ? fixOverride : getEudFix();

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

            dispatchCot("Emergency CANCEL", xml);

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
                    // Emergency payload includes ALERT/CANCEL, handle each case.;
                    handleEmergencyCot(envelopeJson);
                    break;

                case "chat":
                    // GeoChat
                    sendChat(envelopeJson);
                    break;

                default:
                    // Anything else, do nothing
                    break;
            }

        } catch (Exception e) {
            Log.e(TAG, "handleJsonFromWearTak: bad JSON envelope", e);
        }
    }

    public void handleGarminMessage(JSONObject envelope) {
        if (envelope == null) return;
        String msgType = envelope.optString("msgType", "").trim();
        JSONObject payload = envelope.optJSONObject("payload");
        if (msgType.isEmpty() || payload == null) {
            Log.w(TAG, "handleGarminMessage: missing msgType or payload");
            return;
        }

        String envelopeJson = envelope.toString();
        switch (msgType) {
            case "relay_hello":
                Log.i(TAG, "Garmin watch relay connected: "
                        + payload.optString("watchLabel", "Garmin watch"));
                break;
            case "marker":
                sendMarkerCot(envelopeJson, true);
                break;
            case "marker_delete":
                sendMarkerDelete(payload);
                break;
            case "emergency":
                if (!payload.has("callsign")) {
                    try {
                        payload.put("callsign", pluginCallsign);
                    } catch (JSONException e) {
                        Log.e(TAG, "Could not set Garmin emergency callsign", e);
                    }
                }
                // Cancellation must still clear the alert if the phone has lost its fix.
                if ("CANCEL".equalsIgnoreCase(payload.optString("state", ""))) {
                    handleEmergencyCot(envelope.toString(), getEudFix());
                    break;
                }
                EudFix phoneFix = getValidEudFix();
                if (phoneFix == null) {
                    Log.w(TAG, "Garmin emergency rejected: no valid phone location (state="
                            + payload.optString("state", "") + ", uid="
                            + payload.optString("uid", "") + ")");
                    break;
                }
                handleEmergencyCot(envelope.toString(), phoneFix);
                break;
            case "chat":
                try {
                    if (!payload.has("msg") && payload.has("text")) {
                        payload.put("msg", payload.optString("text", ""));
                    }
                    if (!payload.has("cs")) payload.put("cs", pluginCallsign);
                    if (!payload.has("roomUid")) payload.put("roomUid", "All Chat Rooms");
                    if (!payload.has("roomTitle")) payload.put("roomTitle", "All Chat Rooms");
                    sendChat(envelope.toString());
                } catch (JSONException e) {
                    Log.e(TAG, "Could not adapt Garmin chat message", e);
                }
                break;
            case "entity_sync_request":
                Log.i(TAG, "Garmin requested an ATAK entity snapshot");
                break;
            default:
                Log.w(TAG, "Ignoring unsupported Garmin message: " + msgType);
                break;
        }
    }

    public List<Map<String, Object>> getGarminEntitySnapshot(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 50));
        ArrayList<Map<String, Object>> entities = new ArrayList<>();
        MapView mv = mapView;
        if (mv == null || mv.getRootGroup() == null) {
            Log.w(TAG, "getGarminEntitySnapshot: ATAK map is unavailable");
            return entities;
        }

        Collection<MapItem> mapItems = mv.getRootGroup().getItemsRecursive();
        if (mapItems == null) return entities;
        GeoPoint self = null;
        if (mv.getSelfMarker() != null) self = mv.getSelfMarker().getPoint();
        if (self == null || !self.isValid()
                || !PhoneFixValidator.isUsable(self.getLatitude(), self.getLongitude())) {
            Log.w(TAG, "getGarminEntitySnapshot: no valid self position");
            return entities;
        }
        final ArrayList<double[]> distances = new ArrayList<>();
        for (MapItem item : mapItems) {
            if (!(item instanceof PointMapItem)) continue;
            String uid = item.getUID();
            String type = item.getType();
            if (uid == null || uid.isEmpty() || uid.equals(myUid)
                    || type == null || !type.startsWith("a-")) {
                continue;
            }

            GeoPoint point = ((PointMapItem) item).getPoint();
            if (point == null || !point.isValid()) continue;
            double lat = point.getLatitude();
            double lon = point.getLongitude();
            if (Double.isNaN(lat) || Double.isInfinite(lat)
                    || Double.isNaN(lon) || Double.isInfinite(lon)) {
                continue;
            }

            Map<String, Object> entity = new HashMap<>();
            entity.put("uid", uid);
            entity.put("lat", lat);
            entity.put("lon", lon);
            entity.put("type", type);
            entities.add(entity);
            double distance = self.distanceTo(point);
            distances.add(new double[]{distance, entities.size() - 1});
        }

        // Send the nearest units first so the watch map shows what is around the user.
        java.util.Collections.sort(distances, (a, b) -> Double.compare(a[0], b[0]));
        ArrayList<Map<String, Object>> nearest = new ArrayList<>();
        for (int i = 0; i < distances.size() && nearest.size() < limit; i++) {
            nearest.add(entities.get((int) distances.get(i)[1]));
        }
        Log.i(TAG, "getGarminEntitySnapshot: " + nearest.size() + " of "
                + entities.size() + " units selected (self=" + (self != null) + ")");
        return nearest;
    }

    private void sendMarkerDelete(JSONObject payload) {
        String markerUid = optString(payload, "uid", null);
        if (markerUid == null) {
            Log.w(TAG, "sendMarkerDelete: missing marker uid");
            return;
        }

        EudFix fix = getEudFix();
        String now = iso8601(System.currentTimeMillis());
        String stale = iso8601(System.currentTimeMillis() + 120_000);
        String deleteUid = "garmin-delete-" + UUID.randomUUID();
        String xml = "<?xml version='1.0' encoding='UTF-8' standalone='yes'?>"
                + "<event version='2.0' uid='" + escapeXml(deleteUid) + "' type='t-x-d-d' "
                + "time='" + escapeXml(now) + "' start='" + escapeXml(now)
                + "' stale='" + escapeXml(stale) + "' how='h-g-i-g-o'>"
                + "<point lat='" + trimDouble(fix.lat) + "' lon='" + trimDouble(fix.lon)
                + "' hae='" + trimDouble(fix.hae) + "' ce='" + trimDouble(fix.ce)
                + "' le='" + trimDouble(fix.le) + "'/>"
                + "<detail><link uid='" + escapeXml(markerUid) + "'/></detail></event>";
        dispatchCot("Garmin marker delete", xml);
    }

    // =========================================================================================
    // EUD LOCATION HELPERS
    // =========================================================================================

    /** Gets the current ATAK self point, with a safe fallback when no usable fix exists. */
    private EudFix getEudFix() {
        EudFix fix = getValidEudFix();
        return fix != null ? fix : EudFix.defaultFix();
    }

    /** Like {@link #getEudFix()}, but returns null instead of a 0,0 placeholder when there is no usable fix. */
    private EudFix getValidEudFix() {
        try {
            MapView mv = mapView;
            Marker sm = (mv != null) ? mv.getSelfMarker() : null;
            GeoPoint gp = (sm != null) ? sm.getPoint() : null;
            if (gp == null || !gp.isValid()) return null;

            double lat = gp.getLatitude();
            double lon = gp.getLongitude();
            if (!PhoneFixValidator.isUsable(lat, lon)) return null;

            CotPoint point = new CotPoint(gp);
            return new EudFix(lat, lon, point.getHae(), 10.0, 10.0);
        } catch (Throwable t) {
            Log.e(TAG, "getValidEudFix: exception", t);
            return null;
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
    // HELPER FUNCTIONS
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

    private static String iconFromType(String type) {
        if (type == null || type.isEmpty()) return null;
        return COT_MAPPING_2525C + "/" + type.replace("-", "/");
    }

}
