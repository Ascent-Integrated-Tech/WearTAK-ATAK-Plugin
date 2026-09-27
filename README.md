Weartak ATAK Companion Plugin


_________________________________________________________________
PURPOSE AND CAPABILITIES

(General Description)


_________________________________________________________________
STATUS

(In Progress?  Expected release?  Released?  To Who?  When?)

_________________________________________________________________
POINT OF CONTACTS

(Who is developing this)

_________________________________________________________________
PORTS REQUIRED

(This is important for ATO, networking, and other security concerns)

_________________________________________________________________
EQUIPMENT REQUIRED

_________________________________________________________________
EQUIPMENT SUPPORTED

_________________________________________________________________
GARMIN CONNECT IQ (WEARTAK-GARMIN)

The plugin can relay CoT to and from a Garmin watch running WearTAK-Garmin.
It uses Garmin Connect Mobile on the phone as the transport, via the
Connect IQ Mobile SDK 2.4.0. This is an optional feature that is off by
default. The Samsung/Wear OS BLE transport is separate and unchanged.

Requirements
- Garmin Connect Mobile installed on the ATAK EUD, with the watch paired in it.
- WearTAK-Garmin installed on the watch (Connect IQ app ID
  5721f67e-bcc4-47e8-b337-2ad96ee77c0a).
- Garmin Connect Mobile exempt from battery optimization, so Android does
  not suspend it in the background.

Setup
1. Open the WearTAK plugin pane in ATAK and tap "Enable Garmin Connect IQ".
2. Wait for the status to read "Found N Garmin device(s)".
3. On the watch, open WearTAK-Garmin > Settings > Network Preferences and
   turn on ATAK Relay.

"Found N Garmin device(s)" only confirms that Garmin Connect Mobile knows
about the watch. It does not mean the relay is active. The relay is active
once the watch sends relay_hello; check the ATAK logs (tag WTK/Plugin) for
"Garmin Connect IQ message: relay_hello".

Messages from the watch to ATAK
- relay_hello: the watch starts the relay session.
- entity_sync_request: ATAK replies with an "entities" message containing
  up to 50 nearby ATAK units, nearest first.
- marker: creates or updates a map point. The CoT uid is the uid from the
  payload (e.g. garmin-marker-point-1).
- marker_delete: removes the point with that uid, using a t-x-d-d CoT
  sent locally and to the network.
- emergency: emergency alert or cancel.
- chat: GeoChat message, sent to All Chat Rooms by default.

Known limitations
- Deleting a Garmin point in ATAK does not remove it from the watch.
- ATAK does not reply to relay_hello with an acknowledgement. The watch
  should treat successful delivery of relay_hello as connected.

Troubleshooting
- Check logcat for the tags WTK/Plugin, WTK/GarminCIQ and WTK/BleCotBridge.
- If the watch stops reaching ATAK, tap "Disable Garmin Connect IQ", then
  "Enable Garmin Connect IQ", then turn ATAK Relay off and on again on the
  watch. After installing a new watch build, force-stop and reopen ATAK.

_________________________________________________________________
COMPILATION

Copy template.local.properties to local.properties and set sdk.dir.

To build against a local ATAK SDK instead of the TAK artifact repository,
pass the SDK's takdev Gradle plugin and main.jar:

    gradlew.bat -Ptakdev.plugin=<SDK>\atak-gradle-takdev.jar ^
        -Ptak.sdk.main.jar=<SDK>\main.jar :app:assembleCivDebug

Both properties can also be set in local.properties. To target a different
ATAK version, pass -Patak.version=<version> (default 5.5.1). The APK is
written to app\build\outputs\apk\civ\debug\.

The build regenerates app/proguard-gradle-repackage.txt from the root
project name. Do not commit that change.

_________________________________________________________________
DEVELOPER NOTES
