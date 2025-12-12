
package com.atakmap.android.weartak_companion.plugin;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;

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
        cotBridge.sendStandardPli(
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
        );

        // ---------- 2) Send ALERT after 5 seconds ----------
        mainHandler.postDelayed(() -> {
            Log.d(TAG, "sendTestCots: sending ALERT after 5s delay");

            cotBridge.sendEmergencyAlert(
                    "WEARTAK_TEST_ALERT_UID",
                    lat, lon, hae, ce, le,
                    "Ghost-1",
                    "Ghost-1 ALERT",
                    "TROOPS IN CONTACT",
                    "TIC",
                    "WEARTAK_TEST_PLI_UID"
            );

        }, 15000); // 5 seconds



        // ---------- 3) Send CANCEL after 10 seconds ----------
        mainHandler.postDelayed(() -> {
            Log.d(TAG, "sendTestCots: sending CANCEL after 10s delay");

            cotBridge.sendEmergencyCancel(
                    "WEARTAK_TEST_ALERT_UID",
                    lat, lon, hae, ce, le,
                    "Ghost-1"
            );

        }, 30000); // 10 seconds total
    }

}
