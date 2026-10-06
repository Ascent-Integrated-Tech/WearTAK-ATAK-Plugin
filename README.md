Weartak ATAK Companion Plugin

PROJECT SUMMARY

Unlimited Rights are granted to the TAK Product Center. For questions or
project coordination, contact Alex Gorsuch on chat.tak.gov or Signal.
The TAK Forge repository is canonical; GitHub is secondary.

PLUGIN SUMMARY

WearTAK is an ATAK companion plugin that connects ATAK with supported
smartwatches. It relays Cursor-on-Target (CoT) data over Bluetooth Low
Energy between ATAK and Samsung/Wear OS watches, including map markers,
emergency alerts, and GeoChat messages. An optional Garmin Connect IQ
relay is being developed on the separate garmin-connect-iq-integration
branch.


_________________________________________________________________
PURPOSE AND CAPABILITIES

(General Description)


_________________________________________________________________
STATUS

(In Progress?  Expected release?  Released?  To Who?  When?)

_________________________________________________________________
RIGHTS

Unlimited Rights granted to TAK Product Center.

_________________________________________________________________
POINT OF CONTACT

Alex Gorsuch on chat.tak.gov or Signal.

_________________________________________________________________
REPOSITORIES

The TAK Forge repository is canonical; GitHub is secondary.

_________________________________________________________________
PORTS REQUIRED

(This is important for ATO, networking, and other security concerns)

_________________________________________________________________
EQUIPMENT REQUIRED

_________________________________________________________________
EQUIPMENT SUPPORTED

_________________________________________________________________
COMPILATION

This branch targets ATAK 5.8.0 (plugin-api com.atakmap.app@5.8.0.CIV for
the CIV variant). Use JDK 17 to run Gradle 8.14.3 with Android Gradle Plugin
8.13.2, Android SDK platform 36, compile/target SDK 36, and minimum SDK 23.
Java source and bytecode compatibility remain Java 8.

TPP should configure its authenticated takrepo.url/user/password in
local.properties or Gradle properties. The repository resolves takdev 3.+;
an offline/local takdev.plugin JAR must be version 3.5.3 or newer and paired
with the ATAK 5.8 SDK. Do not use a 5.5/5.6 SDK to validate 5.8 compatibility.
This plugin has no native sources, so no NDK installation is required;
if native compilation is added, ATAK 5.8 requires NDK 27.3.13750724.
ATAK-provided core AndroidX libraries remain excluded from implementation;
dependency alignment follows ATAK 5.8 (core 1.17.0 except core-viewtree,
lifecycle 2.10.0, fragment 1.8.9).

Run gradlew.bat :app:testCivDebugUnitTest :app:assembleCivDebug, then
gradlew.bat :app:lintCivRelease :app:assembleCivRelease for release validation.
An archive is source only, not a verified APK or TPP-signed package.
ATAK 5.8 API/build and phone behavior require the matching SDK/repository
and device testing; local tests against an older SDK do not establish them.

Local migration validation used JDK 17 and SDK 36: Java 8 compilation,
21 targeted unit tests, and CIV release lint passed with ATAK 5.5.1 SDK
stubs supplied only through an uncommitted validation init script.
The normal 5.8 debug/release build could not resolve ATAK API dependencies
without an authenticated TAK repository or 5.8 SDK. Release packaging
with the older stubs also stopped at the missing atak.proguard.mapping
property. This source archive is therefore not build-verified on ATAK 5.8.

_________________________________________________________________
DEVELOPER NOTES

SAMSUNG SYSTEM-BONDED WATCH TEST BRANCH

On feature/weartak-samsung-system-pairing, "Use system-bonded WearTAK watch"
is a persisted user opt-in, OFF by default (including upgrades). Starting
the Companion plugin checks for a compatible bonded candidate but never
connects automatically unless this option is enabled. Enable Bluetooth,
grant Nearby Devices permissions to the
ATAK host, and pair the Samsung watch through Android/Galaxy Wearable first.
Run the WearTAK watch app so its A11A BLE service is available.

Discovery uses the previously identified WearTAK address if still bonded;
otherwise it scans for bonded devices advertising WearTAK's A11A service.
A Samsung device name alone is never sufficient. This automatic route
never creates or removes a bond. The original Scan/manual Connect route
is retained and may request a new BLE bond for an unbonded selection.

The toggle controls WearTAK use, not pairing or Galaxy Wearable. It is
greyed out while OFF with no verified compatible bond. Once a bonded
WearTAK advertiser is identified, the toggle becomes available. A previously
identified watch still bonded remains available even with Bluetooth off or
the watch unreachable; availability is not a reachability guarantee.
While Android's Bluetooth service is off, the last confirmed bond is retained
and reverified when Bluetooth returns (or invalidated by a bond-loss event).
Without Nearby Devices permission the bond cannot be verified. If already
ON the control always remains turn-off-able. An enabled preference is never
cleared by transient unreachability, Bluetooth-off, permission loss, or bond
loss; retry discovery continues until the user turns use off.

Turning ON starts automatic connection/reconnection. Turning OFF cancels
the current discovery/setup/retry session and disconnects only WearTAK's
GATT session. Disabled candidate observation may then run without connecting:
unknown candidates are retried using the same capped backoff, and known
bonds are checked every 60 seconds without scanning. Neither operation
removes a bond or changes Galaxy Wearable's connection.

CONNECTED, the connected card, and settings requests are enabled only
after service discovery and successful A11B notification subscription
(CCCD write). Discovery is bounded to 35 seconds and GATT setup to
25 seconds. Failures/disconnects retry at 2, 4, 8, 16, 32, then at most
60-second intervals; successful readiness resets the backoff. Bluetooth
disabled and missing permissions are reported, and periodic retries recover
after Bluetooth/permissions are restored. This does not request permissions
on the user's behalf.

Scan, manual Connect, and deliberate Disconnect turn the opt-in OFF and
persist that choice. They also stop candidate observation for the current
plugin lifecycle so manual scans/connections do not compete with it.
Restarting the plugin resumes identification only, not automatic use;
only a saved ON preference resumes automatic connection. Disconnect does
not immediately reconnect or forget the system bond.
SystemBondedWatchConnection.start()/stop() are the explicit activation seam:
a future connection-mode selector must stop this controller before activating
another transport and start it only when System Bonded Watch is selected.
observe() is identification-only and must also be stopped for other modes.
No selector, Garmin transport, or other feature branches are included here.

For end-to-end bond preservation use the corresponding WearOS Samsung
system-pairing watch version (wearos-tak-civ-samsung-system-pairing,
commit 9a90f83 or its successors). A TPP-signed Companion plugin cannot
update or replace the watch app. The Companion source ZIP is for TPP
build/signing and testing, not certification or a directly installable APK.
Build configuration now targets ATAK 5.8.0; this source supersedes the
50a0081 source package for ATAK 5.8 builds. Behavior on the requested
ATAK 5.8 phone is not proven by local compilation/unit tests.
TPP must provide its normal TAK SDK/build credentials; no local SDK,
local.properties, generated outputs, or signing key is included.
