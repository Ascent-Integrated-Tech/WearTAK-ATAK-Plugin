
package com.atakmap.android.weartak_companion.plugin;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.concurrent.ScheduledExecutorService;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

public class PluginTemplate implements IPlugin {

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane templatePane;
    private TextView connectionStatusTV;
    private String connectionStatus = "DISCONNECTED";

    private static final String TAG = "PluginTemplate";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WearTakBleClient bleClient;

    private BleCotBridge cotBridge;
    private boolean testCotSent = true;

    private MapView mapView;
    private Marker selfMarker;
    private GeoPoint selfGeoPoint;
    private String callsign;
    private String team;
    private Double selfLat;
    private Double selfLon;

    public PluginTemplate(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }

        // obtain the UI service
        uiService = serviceController.getService(IHostUIService.class);

        mapView = MapView.getMapView();

        // initialize the toolbar button for the plugin

        // create the button
        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        pluginContext.getResources().getDrawable(R.drawable.ic_launcher),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        showPane();
                    }
                })
                .build();
    }

    @Override
    public void onStart() {
        if (uiService == null)
            return;

        uiService.addToolbarItem(toolbarItem);

        // Ensure we have a pluginContext
        if (pluginContext == null && serviceController != null) {
            PluginContextProvider ctxProvider =
                    serviceController.getService(PluginContextProvider.class);
            if (ctxProvider != null) {
                pluginContext = ctxProvider.getPluginContext();
                if (pluginContext != null) {
                    pluginContext.setTheme(R.style.ATAKPluginTheme);
                }
            }
        }

        // If we still don't have a context, log and bail; don't crash ATAK
        if (pluginContext == null) {
            android.util.Log.w("PluginTemplate",
                    "onStart: pluginContext is null; skipping BLE initialization");
            return;
        }

        // Lazily create BLE client once
        if (bleClient == null) {
            bleClient = new WearTakBleClient(pluginContext, new WearTakBleClient.StatusListener() {
                @Override
                public void onConnectionStatusChanged(final boolean connected) {
                    connectionStatus = connected ? "CONNECTED" : "DISCONNECTED";
                    mainHandler.post(() -> {
                        if (connectionStatusTV != null) {
                            connectionStatusTV.setText(connectionStatus);
                        }
                    });
                }
            });
        }

        if(bleClient != null){
            bleClient.start();
        }

        // NEW: CoT bridge init
        if (cotBridge == null) {
            Log.d(TAG, "onStart: creating BleCotBridge instance");
            cotBridge = new BleCotBridge();
        }

        Log.d(TAG, "onStart: sending test CoTs");
        sendTestCots();
    }


    @Override
    public void onStop() {
        // the plugin is stopping, remove the button from the toolbar
        if (uiService == null)
            return;

        uiService.removeToolbarItem(toolbarItem);

        // clean up BLE on plugin stop
        if (bleClient != null) {
            bleClient.stop();
        }
    }

    private void showPane() {
        // instantiate the plugin view if necessary
        if(templatePane == null) {
            // Remember to use the PluginLayoutInflator if you are actually inflating a custom view
            // In this case, using it is not necessary - but I am putting it here to remind
            // developers to look at this Inflator

            View paneView = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);

            templatePane = new PaneBuilder(PluginLayoutInflater.inflate(pluginContext,
                    R.layout.main_layout, null))
                    // relative location is set to default; pane will switch location dependent on
                    // current orientation of device screen
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    // pane will take up 50% of screen width in landscape mode
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    // pane will take up 50% of screen height in portrait mode
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();

            connectionStatusTV = paneView.findViewById(R.id.connection_status);
            connectionStatusTV.setText(connectionStatus);
        }

        // if the plugin pane is not visible, show it!
        if(!uiService.isPaneVisible(templatePane)) {
            uiService.showPane(templatePane, null);
        }

        // optional: force a check when pane opens
        /*if (bleClient != null) {
            bleClient.checkImmediate();
        }*/
    }

    /*private void sendTestCots() {
        if (cotBridge == null) {
            Log.w(TAG, "sendTestCots: cotBridge is null, cannot send");
            return;
        }

        // Nairobi approx
        double lat = -1.286389;
        double lon = 36.817223;
        double hae = 1700.0;   // arbitrary altitude (m)
        double ce  = 10.0;     // circular error (m)
        double le  = 10.0;     // linear error (m)

        // 1) Standard PLI
        cotBridge.sendStandardPli(
                "WEARTAK_TEST_PLI_UID",  // uid
                lat,
                lon,
                hae,
                ce,
                le,
                "WearTAK test PLI from plugin",  // remarks
                "Ghost-1",                        // callsign
                "teamLead",                       // role
                "Blue",                           // teamValue
                90.0,                             // courseDeg
                1.5,                              // speedMps
                null,                             // batdokDetailXml
                null                              // hailCotDetailXml
        );

        // 2) Emergency ALERT
        cotBridge.sendEmergencyAlert(
                "WEARTAK_TEST_ALERT_UID",    // uid
                lat,
                lon,
                hae,
                ce,
                le,
                "Ghost-1",                    // callsign (wearer)
                "Ghost-1 ALERT",              // emergencyCallsign
                "TROOPS IN CONTACT",          // emergencyCategory
                "TIC",                        // emergencyDesc
                "WEARTAK_TEST_PLI_UID"        // linkUid back to PLI
        );



        // 3) Emergency CANCEL
        cotBridge.sendEmergencyCancel(
                "WEARTAK_TEST_ALERT_UID",    // same uid as alert to cancel it
                lat,
                lon,
                hae,
                ce,
                le,
                "Ghost-1"                    // callsign
        );
    }*/

    private void sendTestCots() {
        if (cotBridge == null) {
            Log.w(TAG, "sendTestCots: cotBridge is null, cannot send");
            return;
        }

        Log.d(TAG, "sendTestCots: starting test CoT sequence (PLI → ALERT → CANCEL)");

        // Nairobi approx
        double lat = -1.286389;
        double lon = 36.817223;
        double hae = 1700.0;
        double ce  = 10.0;
        double le  = 10.0;

        // ---------- 1) Send STANDARD PLI immediately ----------
        Log.d(TAG, "sendTestCots: sending PLI immediately");
        /*cotBridge.sendStandardPli(
                "WEARTAK_TEST_PLI_UID",
                lat, lon, hae, ce, le,
                "WearTAK test PLI from plugin",
                "Ghost-1",
                "teamLead",
                "Blue",
                90.0,
                1.5,
                null,
                null
        );*/

        cotBridge.sendStandardPli(buildStubMarkerEnvelopeJson());

        // ---------- 2) Send ALERT after 5 seconds ----------
        mainHandler.postDelayed(() -> {
            Log.d(TAG, "sendTestCots: sending ALERT after 5s delay");

            /*cotBridge.sendEmergencyAlert(
                    "WEARTAK_TEST_ALERT_UID",
                    lat, lon, hae, ce, le,
                    "Ghost-1",
                    "Ghost-1 ALERT",
                    "TROOPS IN CONTACT",
                    "TIC",
                    "WEARTAK_TEST_PLI_UID"
            );*/

            cotBridge.sendEmergencyAlert(buildStubEmergencyAlertEnvelopeJson());

        }, 15000); // 5 seconds



        // ---------- 3) Send CANCEL after 10 seconds ----------
        mainHandler.postDelayed(() -> {
            Log.d(TAG, "sendTestCots: sending CANCEL after 10s delay");

            /*cotBridge.sendEmergencyCancel(
                    "WEARTAK_TEST_ALERT_UID",
                    lat, lon, hae, ce, le,
                    "Ghost-1"
            );*/

            cotBridge.sendEmergencyCancel(buildStubEmergencyCancelEnvelopeJson());

        }, 30000); // 10 seconds total

        mainHandler.postDelayed(() -> {
            Log.d(TAG, "sendTestCots: sending CHAT after 10s delay");

            /*cotBridge.sendEmergencyCancel(
                    "WEARTAK_TEST_ALERT_UID",
                    lat, lon, hae, ce, le,
                    "Ghost-1"
            );*/

            cotBridge.sendChat(buildStubChatEnvelopeJson());

        }, 30000); // 10 seconds total
    }

    private static String buildStubMarkerEnvelopeJson() {
        return "{\n" +
                "  \"proto_version\": 1,\n" +
                "  \"msg_type\": \"marker\",\n" +
                "  \"msg_id\": \"1dee7839-274c-433a-9052-c4ee8e7f0663\",\n" +
                "  \"timestamp\": \"1765468601177\",\n" +
                "  \"source\": \"wearos\",\n" +
                "  \"payload\": {\n" +
                "    \"alt\": 152.10000610351562,\n" +
                "    \"battery_percent\": 100,\n" +
                "    \"callsign\": \"WEAROS-1075\",\n" +
                "    \"ce\": 17.749000549316406,\n" +
                "    \"icon_path\": \"COT_MAPPING_2525C/a-f/a-f-G\",\n" +
                "    \"lat\": 41.8867844,\n" +
                "    \"le\": 0.6344082951545715,\n" +
                "    \"lon\": -87.6672634,\n" +
                "    \"marker_id\": \"1dee7839-274c-433a-9052-c4ee8e7f0663\",\n" +
                "    \"marker_type\": \"a-f-G\",\n" +
                "    \"time_stale\": \"2026-12-11T15:56:41.152Z\",\n" +
                "    \"time_start\": \"2025-12-11T15:56:41.152Z\"\n" +
                "  }\n" +
                "}";
    }

    private static String buildStubEmergencyAlertEnvelopeJson() {
        return "{\n" +
                "  \"proto_version\": 1,\n" +
                "  \"msg_type\": \"emergency\",\n" +
                "  \"msg_id\": \"f211cb62-e200-467b-99a7-72ea84d501b7\",\n" +
                "  \"timestamp\": \"2025-12-11T15:57:07.115199Z\",\n" +
                "  \"source\": \"wearos\",\n" +
                "  \"payload\": {\n" +
                "    \"bat\": 100,\n" +
                "    \"catg\": \"Manual SOS Alert\",\n" +
                "    \"callsign\": \"WEAROS-1075\",\n" +
                "    \"desc\": \"SOS Alert Pressed by User\",\n" +
                "    \"state\": \"ALERT\",\n" +
                "    \"tStale\": \"2025-12-11T16:12:07.093Z\",\n" +
                "    \"tStart\": \"2025-12-11T15:57:07.093Z\",\n" +
                "    \"uid\": \"f211cb62-e200-467b-99a7-72ea84d501b7\"\n" +
                "  }\n" +
                "}";
    }


    private static String buildStubEmergencyCancelEnvelopeJson() {
        return "{\n" +
                "  \"proto_version\": 1,\n" +
                "  \"msg_type\": \"emergency\",\n" +
                "  \"msg_id\": \"f211cb62-e200-467b-99a7-72ea84d501b7\",\n" +
                "  \"timestamp\": \"2025-12-11T16:00:07.115199Z\",\n" +
                "  \"source\": \"wearos\",\n" +
                "  \"payload\": {\n" +
                "    \"bat\": 100,\n" +
                "    \"catg\": \"Manual SOS Alert\",\n" +
                "    \"callsign\": \"WEAROS-1075\",\n" +
                "    \"desc\": \"SOS Alert Pressed by User\",\n" +
                "    \"state\": \"CANCEL\",\n" +
                "    \"tStale\": \"2025-12-11T16:12:07.093Z\",\n" +
                "    \"tStart\": \"2025-12-11T15:57:07.093Z\",\n" +
                "    \"uid\": \"f211cb62-e200-467b-99a7-72ea84d501b7\"\n" +
                "  }\n" +
                "}";
    }

    private static String buildStubChatEnvelopeJson() {
        return "{\n" +
                "  \"proto_version\": 1,\n" +
                "  \"msg_type\": \"chat\",\n" +
                "  \"msg_id\": \"b7af2d3f-9e2a-4c6c-a9c7-1b9d27d1f8e1\",\n" +
                "  \"timestamp\": \"2025-12-11T16:05:07.115199Z\",\n" +
                "  \"source\": \"wearos\",\n" +
                "  \"payload\": {\n" +
                "    \"uid\": \"b7af2d3f-9e2a-4c6c-a9c7-1b9d27d1f8e1\",\n" +
                "    \"roomUid\": \"All Chat Rooms\",\n" +
                "    \"roomTitle\": \"WearTAK Ops\",\n" +
                "    \"msg\": \"WearTAK: test GeoChat message from watch envelope.\",\n" +
                "    \"tStart\": \"2025-12-11T16:05:07.093Z\",\n" +
                "    \"tStale\": \"2025-12-11T16:15:07.093Z\",\n" +
                "    \"callsign\": \"WEAROS-1075\",\n" +
                "    \"hr\": 82,\n" +
                "    \"bat\": 100\n" +
                "  }\n" +
                "}";
    }


}
