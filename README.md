Weartak ATAK Companion Plugin

PROJECT SUMMARY

Unlimited Rights are granted to the TAK Product Center. For questions or
project coordination, contact Alex Gorsuch on chat.tak.gov or Signal.
The TAK Forge repository is canonical; GitHub is secondary.

PLUGIN SUMMARY

WearTAK is an ATAK companion plugin that connects ATAK with supported
smartwatches. It relays Cursor-on-Target (CoT) data over Bluetooth Low
Energy between ATAK and Samsung/Wear OS watches, including map markers,
emergency alerts, and GeoChat messages. This integration branch includes
the Garmin Connect IQ relay and an exclusive three-mode connection selector.


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

THREE-MODE SELECTOR INTEGRATION

The connection-mode button offers three real, mutually exclusive routes:
- Use Samsung Wearable Bond: explicit opt-in to automatic discovery,
  connection and reconnection through the existing Samsung controller.
- Traditional BLE Pairing: manual Scan/Connect and BLE watch settings.
  No automatic Samsung candidate observation runs in this mode.
- Garmin Connect IQ: starts the Garmin Connect Mobile SDK relay, including
  watch CoT/messages and requested nearby entity snapshots. Install WearTAK
  on the paired Garmin watch and enable Settings > Network Preferences >
  ATAK Relay. Retry Garmin Connect IQ restarts this route if initialization
  or discovery failed. Direct BLE settings are not available for Garmin.

Selecting a different mode stops the prior route (including Samsung
observation, timers, scans and GATT, or Garmin SDK listeners), clears the
old connected card/settings state, and then starts the new route. Queued
callbacks are generation-guarded, including after switching away and back.
Re-selecting the current mode does not disconnect it. Plugin shutdown stops
all transport activity without clearing the saved selection.

The host preferences file weartak_companion_prefs owns connection_mode.
Saved traditional_ble from the earlier UI-only selector is honored; its
plugin-context preference is read once if the host value is absent. With
no selector value, legacy use_system_bonded_watch=true migrates to Samsung;
false/absent migrates to Traditional. Unknown mode values fall back to
Traditional, never automatic use. New values are samsung_system_bond,
traditional_ble and garmin_connect_iq. The legacy Samsung boolean stays
synchronized for downgrade compatibility; preferred BLE device keys are
unchanged. Restart resumes the saved route; Traditional never auto-connects.

Deliberate BLE Disconnect selects/persists Traditional before disconnecting,
so Samsung retries cannot immediately reconnect. Scan/manual Connect are
available only in Traditional. To stop Garmin, select another mode; plugin
shutdown also stops it. Switching does not delete Android Bluetooth bonds.

This branch is stacked on WEARTAK-53 and merges Garmin feature tip 548874d.
Its requests depend on Samsung !5/#9 and Garmin !6/#10; the stacked diff
includes Garmin changes until that independent feature lands. ATAK 5.8
configuration remains intact. No existing requests have been merged.

Integration validation: normal Gradle targeted tests/compile are blocked
at configuration by the unavailable local takdev plugin in this worktree.
28 targeted tests passed using the existing cached JUnit 4.13.2 runner
(selector, Samsung controller, phone fix validator). All main Java sources compiled
at Java 8 compatibility against older ATAK 5.5.1 API stubs, SDK 36 and
cached Android/Garmin dependencies with a generated resource-ID stub.
All source XML parsed. These fallback checks do not verify ATAK 5.8 API
compatibility, Android resource linking, APK packaging, or phone/watch behavior.

SAMSUNG TRANSPORT DETAILS (SELECTOR-OWNED)

Samsung mode is a persisted explicit opt-in, not the new-install default.
Enable Bluetooth, grant Nearby Devices permissions to the
ATAK host, and pair the Samsung watch through Android/Galaxy Wearable first.
This option reuses the existing Android Bluetooth bond established through
Galaxy Wearable without changing or removing that pairing.
Run the WearTAK watch app so its A11A BLE service is available.

Discovery uses the previously identified WearTAK address if still bonded;
otherwise it scans for bonded devices advertising WearTAK's A11A service.
A Samsung device name alone is never sufficient. This automatic route
never creates or removes a bond. The original Scan/manual Connect route
is retained and may request a new BLE bond for an unbonded selection.

The selector controls WearTAK use, not pairing or Galaxy Wearable.
Samsung mode can be selected before a candidate is available; it reports
unavailability and retries, rather than claiming a reachable watch.
While Android's Bluetooth service is off, the last confirmed bond is retained
and reverified when Bluetooth returns (or invalidated by a bond-loss event).
Without Nearby Devices permission the bond cannot be verified.
The selected mode is never
cleared by transient unreachability, Bluetooth-off, permission loss, or bond
loss; retry discovery continues until the user changes mode or disconnects.
Changing mode cancels discovery/setup/retries and disconnects only WearTAK's
GATT session, never Galaxy Wearable or the system bond.

CONNECTED, the connected card, and settings requests are enabled only
after service discovery and successful A11B notification subscription
(CCCD write). Discovery is bounded to 35 seconds and GATT setup to
25 seconds. Failures/disconnects retry at 2, 4, 8, 16, 32, then at most
60-second intervals; successful readiness resets the backoff. Bluetooth
disabled and missing permissions are reported, and periodic retries recover
after Bluetooth/permissions are restored. This does not request permissions
on the user's behalf.

SystemBondedWatchConnection.start()/stop() are the explicit activation seam:
the selector stops this controller before activating another transport and
starts it only when Samsung is selected. observe() remains available to
other callers but is not used by this selector, so Traditional and Garmin
have no competing Samsung observation.

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
