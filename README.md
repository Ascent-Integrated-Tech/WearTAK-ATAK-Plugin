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
The optional Garmin Connect IQ
transport uses Garmin Connect Mobile on the phone rather than the
Samsung/Wear OS BLE connection.

GARMIN SETUP

Pair the Garmin watch in Garmin Connect Mobile and install WearTAK-Garmin.
On the watch, select Settings > Network Preferences and enable ATAK Relay.
In the ATAK plugin device pane, select Garmin Connect IQ. The connection
badge and status text report the phone/watch link; finding a watch alone
does not confirm the WearTAK app is installed or that a message arrived.
The relay supports watch markers, emergencies, direct/broadcast GeoChat,
and requested snapshots of nearby ATAK units. New emergency alerts and
nearby-unit snapshots require a valid phone location; cancellation remains
available if the phone loses GPS. Garmin transport is optional and does
not replace the Samsung/Wear OS setup.

GROUP CHAT (WEARTAK-118)

Incoming remote ATAK chat is forwarded with the normalized conversation UID/title,
an explicit `isGroup` flag, and `participantUids` from ATAK's chat bundle. Local direct
conversations normalize to the remote sender; named conversations must resolve to
an actual ATAK GroupContact. All Chat Rooms remains an explicit broadcast room.
Matching watches aggregate different senders in the same group and reply with its
room UID/title and group flag. Legacy watch replies to known group UIDs also work.

Named group replies resolve the current writable GroupContact and its filtered
individual recipients, then call ATAK's
`sendMessage(String, GroupContact, String[], String[])` with the group identity and
recipient UIDs. Unknown/unwritable groups, empty recipients, missing targets, and
missing chat managers fail with logs; they never fall back to broadcast. Only the
explicit All Chat Rooms target uses the existing broadcast-contact API. Direct
GeoChat now resolves a real remote IndividualContact with a GeoChat endpoint and
uses `sendMessage(String, List<Contact>)` with only that contact, preserving ATAK's
native direct conversation/history rather than dispatching private XML to broadcast.
Unknown/self/broadcast-like direct targets are rejected with logs. Native sends run
on ATAK's main thread.

The watch picker offers **All Chat Rooms** to initiate native send-to-all chat and
**Select User for Chat** to select/confirm a person on the map and create/open a
direct conversation in the normal watch chat-room list. Direct replies retain the selected
UID and never fall back to broadcast; map cancellation sends nothing.
The watch pins All Chat Rooms first and Select User for Chat second; direct/named
group rooms follow in descending latest-received-message order, updated on arrival.
The broadcast room is not duplicated among those rooms.

WIRE CONTRACT: version-1 envelopes retain `msgType`, `msgUid`, and `payload`.
Phone-to-watch `chat_received` payload retains `uid` (message ID), `senderUid`,
`senderCallsign`, `msg`, `receivedAt` (epoch milliseconds), and adds
`conversationUid`, `conversationTitle`, `isGroup` (plus group `participantUids`).
Watch-to-phone `chat` payload uses `roomUid`, `roomTitle`, `isGroup`, `msg` or `text`,
and `cs`; direct messages also carry matching `recipientUid`/`replyTo`.
Legacy `rmUid`/`rmTitle` remain emitted/accepted by WearOS. Conflicting destination
or message aliases are rejected; group sends cannot use direct-recipient fallback.
Room titles/callsigns are display metadata, never broadcast-routing defaults.
The WearOS BLE path supports these named groups. The Garmin adapter shares the
normalizer and native direct/broadcast routing but rejects named groups, including
legacy replies resolving to a GroupContact. Its session-correlated `relay_status`
advertises `chatGroups: false` and `reliableDelivery: false`; only a separately
verified group-aware route may enable named Garmin groups.

Named incoming groups must list the paired phone among their recipient UIDs.
Named replies containing ATAK broadcast-like/fake-group or TADIL-J destinations
are rejected because ATAK would otherwise promote those recipients to broadcast.

This supports existing groups, not watch group creation or historical chat sync.
Phone-authored outgoing messages and watch messages attributed to the phone are
not mirrored back; incoming remote messages are relayed while BLE is connected.
BLE enqueue does not acknowledge watch receipt, and no private-messenger integration
is added. Older watch builds still treat received chat as sender DMs; deploy both
updated builds for group-room support.

Hardware acceptance remains pending: with both updated builds and BLE connected,
send to an existing group containing the paired phone from two remote ATAK devices.
Verify one watch room with both sender callsigns; preset and keyboard replies must
reach that ATAK group and its members, not an outsider. Test a simultaneous direct
chat, All Chat Rooms, duplicates/echo suppression, and a new inbox room arriving
while replying. Delete or empty the phone group before replying and verify rejection
logs without broadcast. Repeat reception/replies after BLE reconnect (no offline
history guarantee). Standalone TAK watch tests are documented in the watch README.
Also start with an empty watch inbox: initiate All Chat Rooms and verify native ATAK
broadcast behavior; use Select User for Chat to cancel, then select/confirm one person and send
by preset and keyboard. Verify only that person receives it and ATAK records the
direct conversation. Remove the ATAK contact before sending and verify logged rejection
without unrelated delivery. Phone-authored outgoing messages remain outside watch mirroring.

While a bonded WearTAK watch is connected over BLE, incoming ATAK emergency
CoT (`b-a-o`, `b-a-o-*`, and `b-a-g`) and cancellations are forwarded to
the watch's existing map/Bloodhound alert handling. The BLE envelope uses
`msg_type: "phone_emergency_cot"` with the original CoT XML in `payload.cot`.
The original UID, sender, location, and event times are preserved.
Watch-origin emergency UIDs are tracked in a bounded set to suppress echoes
back to the watch. Forwarding stops when the plugin stops or BLE disconnects;
alerts received while disconnected are not queued for later replay.
Local phone emergency notifications and imported ATAK alert/cancel notifications
are also observed, since successfully imported events bypass fallback CoT listeners.
Duplicate notifications of the same CoT are suppressed. A successful BLE queue
operation is not a watch delivery acknowledgement.

SETTINGS AND P12 PROVISIONING (WEARTAK-90)

By default, connecting or refreshing only reads watch settings. Edit the watch
callsign and server settings, choose the server's P12 file/password if needed,
then tap Send to Watch. Wait for the successful save acknowledgement before
disconnecting. Uploaded credentials stay on the watch and watch settings remain
editable; one-time provisioning does not replace its team or role.

Manage watch settings from this phone is an explicit opt-in. It copies phone
identity, syncs on reconnect, and keeps managed watch settings locked even while
temporarily disconnected. Uncheck it and send again to release management while
retaining saved certificates and current reporting settings. Unpairing also
releases management, but restores the pre-management reporting settings.


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

Production CIV debug resource-link/Java compilation and JVM reconciliation tests
are described below. These local checks do not establish ATAK 5.8 compatibility
or release packaging/signing.

_________________________________________________________________
DEVELOPER NOTES

THREE-MODE SELECTOR INTEGRATION

The connection-mode button offers three real, mutually exclusive routes:
- Use Samsung Wearable Bond: explicit opt-in to automatic discovery,
  connection and reconnection through the existing Samsung Wearable mode controller.
  The name refers to reusing the Android system Bluetooth bond established via
  Galaxy Wearable, not a Samsung API integration or a new pairing operation.
- Traditional BLE Pairing: manual Scan/Connect and BLE watch settings.
  No automatic Samsung Wearable candidate observation runs in this mode.
- Garmin Connect IQ: starts the Garmin Connect Mobile SDK relay, including
  watch CoT/messages and requested nearby entity snapshots. Install WearTAK
  on the paired Garmin watch and enable Settings > Network Preferences >
  ATAK Relay. Retry Garmin Connect IQ restarts this route if initialization
  or discovery failed. Direct BLE settings are not available for Garmin.

Selecting a different mode stops the prior route (including Samsung Wearable
observation, timers, scans and GATT, or Garmin SDK listeners), clears the
old connected card/settings state, and then starts the new route. Queued
callbacks are generation-guarded, including after switching away and back.
Re-selecting the current mode does not disconnect it. Plugin shutdown stops
all transport activity without clearing the saved selection.

The host preferences file weartak_companion_prefs owns connection_mode.
Saved traditional_ble from the earlier UI-only selector is honored; its
plugin-context preference is read once if the host value is absent. With
no selector value, legacy use_system_bonded_watch=true migrates to Samsung Wearable;
false/absent migrates to Traditional. Unknown mode values fall back to
Traditional, never automatic use. New values are samsung_system_bond,
traditional_ble and garmin_connect_iq. The legacy Samsung Wearable boolean stays
synchronized for downgrade compatibility; preferred BLE device keys are
unchanged. Restart resumes the saved route; Traditional never auto-connects.

Deliberate BLE Disconnect selects/persists Traditional before disconnecting,
so Samsung Wearable retries cannot immediately reconnect. Scan/manual Connect are
available only in Traditional. To stop Garmin, select another mode; plugin
shutdown also stops it. Switching does not delete Android Bluetooth bonds.

MR !14 is reconciled with canonical develop `2bc0bf7`, including WEARTAK-87/90/
99/100/78/112/118. Samsung Wearable bond reuse, exclusive transport ownership and the
existing mode-selector UI remain unchanged. Mode teardown also removes native
chat/emergency/point listeners and closes Bloodhound correlation before starting
the new route. Readiness and point revisions stay scoped to the active BLE session.

Garmin chat uses the same received-payload builder as BLE: `uid` is the message
ID, not the sender or room; `senderUid`, `senderCallsign`, `msg`, `receivedAt`
(epoch milliseconds), `conversationUid`, `conversationTitle`, `isGroup`, and
group `participantUids` retain their wire meanings. Direct and exact All Chat Rooms
messages can be handed to Connect IQ. Named incoming groups are rejected before
SDK handoff; outgoing named groups are rejected before native dispatch. No missing
or conflicting destination can fall back to broadcast. Updated Garmin watches
reject unsupported named groups before their shared outbox using the false capability.
This adapter does not claim relay-result acknowledgements or TAK delivery.

Garmin deletion is explicitly unsupported because this adapter has no negotiated
shared-point revision session. It never emits the old external `t-x-d-d` deletion.
WEARTAK-112's paired BLE local-only revision-gated removal remains intact.
Garmin settings, Bloodhound synchronization and phone live marker/emergency
forwarding remain outside this integration; Garmin watch-origin marker/emergency
and requested nearby entity snapshots retain their existing route.

Reconciliation validation uses Gradle 8.14.3/AGP 8.13.2, Android SDK 36 and
the installed ATAK 5.5.1.8 development plugin/main.jar:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat --offline "-Ptakdev.plugin=C:\Projects\ATAK-CIV-5.5.1.8-SDK\atak-gradle-takdev.jar" "-Ptak.sdk.main.jar=C:\Projects\ATAK-CIV-5.5.1.8-SDK\main.jar" :app:testCivDebugUnitTest :app:compileCivDebugJavaWithJavac --console=plain
```

The production CIV debug resource-link/Java compile and all 124 JVM tests pass
(zero failures/errors, including 11 Garmin chat/handshake regressions)
against that installed SDK, not ATAK 5.8 APIs. The configured ATAK target remains
5.8.0; its remote SDK tuple is unavailable offline. Full target-SDK compatibility,
APK packaging/signing and device acceptance still require the matching TAK SDK
and TPP environment. No SDK, credentials, signing keys or local.properties are
included.

Hardware acceptance is pending: switch between every mode while discovery,
GATT setup, queued writes, settings snapshots/certificate selection or Garmin
callbacks are pending; ensure only the selected transport survives, old callbacks
cannot affect the new session, and Android/Galaxy Wearable bonds survive switching
and disconnect. Repeat BLE chat/groups, emergencies, managed/unmanaged provisioning,
point update/delete and Bloodhound tests after mode switches/reconnect. On Garmin,
verify direct/broadcast inbox/replies, false group capability and rejection of
named groups without fallback, handoff-only status, SDK retry, and entity snapshots.
Confirm a third TAK peer receives no deletion from either watch transport.

SAMSUNG TRANSPORT DETAILS (SELECTOR-OWNED)

Samsung Wearable mode is a persisted explicit opt-in, not the new-install default.
Enable Bluetooth, grant Nearby Devices permissions to the
ATAK host, and pair the Samsung watch through Galaxy Wearable first.
This option reuses the existing Android Bluetooth bond established through
Galaxy Wearable without changing or removing that pairing.
Run the WearTAK watch app so its A11A BLE service is available.
On Android 6 through 11, BLE scanning requires the ATAK host's location
permission and legacy Bluetooth permissions. Android 12 and later use
Nearby Devices permissions instead.

Discovery uses the previously identified WearTAK address if still bonded;
otherwise it scans for bonded devices advertising WearTAK's A11A service.
A Samsung device name alone is never sufficient. This automatic route
never creates or removes a bond. The original Scan/manual Connect route
is retained and may request a new BLE bond for an unbonded selection.

The selector controls WearTAK use, not pairing or Galaxy Wearable.
Samsung Wearable mode can be selected before a candidate is available; it reports
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
starts it only when Samsung Wearable is selected. observe() remains available to
other callers but is not used by this selector, so Traditional and Garmin
have no competing Samsung Wearable observation.

BLE settings are isolated per device/session. Mode changes, manual scans/
connections, disconnects and trust loss clear the server adapter, editors,
certificate display, callsign and snapshot state. Send stays disabled until
this session receives a snapshot containing takServerList and reportIntSecs;
partial settings_changed messages alone do not unlock a new session. Queued
previous-session settings and certificate-picker callbacks are discarded.

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
Paired BLE Bloodhound synchronization (WEARTAK-78)

While connected to a bonded WearTAK watch, starting native ATAK Bloodhound
shares the same CoT target UID with the watch. A watch start selects native
ATAK Bloodhound tracking (not route navigation). Ending tracking on either
device ends that shared tracking session on both. This never cancels the
underlying alert or deletes its marker. A missing local target is represented
by a local marker using the watch-supplied callsign and coordinates.

The newline-delimited JSON protocol uses `msg_type=bloodhound_control` and a
payload containing `action` (`start` or `stop`), UUID `session_id`, and
`target_uid`. Start may also carry `target_callsign`, `lat`, `lon`, and `hae`;
coordinates are required when ATAK does not already have the target.
Replies use `msg_type=bloodhound_control_result`, with `session_id`, `action`,
Boolean `ok`, and optional `error`. Local and watch failures are shown as
ATAK toast notifications and logged. Native event echoes and duplicate starts
are suppressed; stops require both the current session ID and target UID.
Repeated stops are acknowledged only for the original session/target pair.
Command results correlate both action and session ID. Missing results time out
after five seconds and failures are visibly reported; a queued BLE write alone
does not confirm synchronization. Simultaneous starts awaiting acknowledgement
converge on the lexicographically smaller session ID. The losing incoming start
receives `ok=false`, `error=Superseded by simultaneous tracking start`; a late
negative result or timeout for the losing local start cannot clear the winner.
`action=update` carries the same session ID, target UID, callsign and coordinates
without an ACK. Native target point/callsign changes publish updates; matching
incoming updates modify only this bridge's fallback marker, never an existing
locally sourced target, and do not restart tracking or echo. Direct CoT import
relinquishes fallback ownership to the local source. Stale, mismatched and
disconnected updates are ignored.
Non-bridge point/callsign edits relinquish ownership immediately, before
the import-complete event, so companion snapshots cannot overwrite a newly
authoritative source. ATAK's same-UID MarkerImporter updates the existing marker
in place; source handoff neither deletes it nor stops/restarts native tracking.
Disconnect/unpair clears shared correlation, without stopping normal local
tracking; reconnect never replays or resumes the disconnected shared session.
Unpaired native ATAK behavior is unchanged.

ATAK 5.5.1.8 SDK integration, verified against local `main.jar` bytecode:
`BloodHoundTool._onBeginLink` sets target metadata `hounding=true`;
`_onEndLink` removes it. `MapItem.addOnMetadataChangedListener("hounding", ...)`
observes actual tracking edges, not dialog/HUD visibility or generic tool
activation. Existing and newly added map items are observed. The host
`MapActivity.getMapComponent(BloodHoundMapComponent.class)` supplies the native
component. ATAK exposes no public tool getter, so a guarded reflective lookup
of that component's protected `_buttonTool` field is necessary on this SDK.
`BloodHoundTool.getRunning()` and `getEndItem()` confirm current native state.
Its public `getHoundReceiver().onReceive(...)` is invoked on the main thread
with `BloodHoundTool.BLOOD_HOUND` and `uid`: this starts a new target or toggles
an existing target off. Synchronous invocation prevents a queued toggle from
affecting a different target after the user switches tracking. Missing or
incompatible host APIs produce a negative result instead of manipulating
alerts. Future ATAK SDK versions must revalidate this compatibility seam.
Native `onButtonClicked()` ends active tracking through `stop()`, which calls
`_onEndLink()`; the watched metadata edge therefore includes the native close
action as well as UID-toggle stops and target replacement.

Focused local JVM coverage: `:app:testCivDebugUnitTest --tests
com.weartak.atak.weartak_companion.plugin.BloodhoundSyncTest`.
Device validation still requires bonded watch + ATAK hardware: test native
and watch starts, both tracking-end actions, fast target replacement, stale
stop rejection, unpair/disconnect, reconnect without replay, and unchanged
alert/marker lifetime.
### WEARTAK-100: live phone point relay

The companion forwards ATAK marker creation and subsequent point, type, and
metadata changes to the active bonded watch after notification subscription
completes (services ready). Existing map points are observed for future edits,
not automatically uploaded. Disconnect, reconnect, or switching watches does
not trigger an offline replay or bulk synchronization.

The envelope remains:

```json
{"msg_type":"phone_marker_cot","payload":{"cot":"<event ...>...</event>"}}
```

Supported types are exactly `a-f-G`, `a-u-G`, `a-h-G`, `a-n-G`, and
`b-m-p-s-p-i`. Self/PLI, emergency, non-exportable items, shapes, routes, and
unsupported types are excluded. Exported identity must match the map item,
coordinates must be in range, HAE/CE/LE finite, and all timestamps positive.
The ATAK SDK export is sent unchanged: UID, type, point, time/start/stale, and
detail are not rewritten. Note that ATAK's default marker exporter itself
generates current time/start and its configured stale interval; this is not a
reconstruction of the original inbound network packet.

Watch imports carry an internal ATAK import-source flag. The resulting marker
snapshot suppresses immediate echoes and identical later notifications, not
every future use of that UID. A phone edit to the point or exported details
releases suppression. Failed imports have a bounded five-second pending guard.
Duplicate edit notifications are coalesced; all map, point, metadata, and type
listeners and pending callbacks are removed on plugin stop or item removal.
Queued BLE chunks and callbacks are session-scoped so they cannot transfer an
old watch's pending data to a new session. A successful enqueue is not a watch
delivery acknowledgement; asynchronous GATT failures remain reported as errors.

### WEARTAK-112: explicit shared-point deletion

**Local paired-device mirrors only.** Watch deletion removes only the eligible
local phone map copy; phone local deletion echoes only to the eligible paired-watch
copy. No deletion is sent to TAK servers, multicast, Ditto, SITX, plugins/mesh, or
other devices. Incoming mirrored removal cannot re-echo. Watch Clear/Last/All is
immediate local removal even without BLE and no longer generates network clear CoT.

ATAK 5.5.1.8 SDK inspection shows that local
`MapItemDeleteReceiver` calls `removeFromGroup()`. `CotEventFactory` does not
turn removed items into expired events; its default marker exporter creates
fresh timestamps. ATAK's remote deletion importer instead accepts a
`t-x-d-d` task with a target `<link>` and optional `__forcedelete`, which is not
the shared-point contract. Neither importer nor external dispatcher is invoked
for the new deletion envelope; the companion uses local `removeFromGroup()`.

Both peers add `payload.revision` (opaque UUID) to live marker envelopes without
rewriting the XML or existing marker fields. Deletion references that exact revision:

```json
{"msg_type":"marker_delete","payload":{"targetUid":"shared-point-uid","revision":"previously-exchanged-revision"}}
```

Only supported, non-PLI/non-emergency markers exchanged in the current bonded,
services-ready BLE session qualify. A matching revision plus unchanged exported
content is required before incoming deletion can remove an observed ATAK marker.
Phone edits replace the shared revision and relinquish watch-echo suppression.
Local ATAK removal emits a deletion only for an unchanged shared marker. Incoming
removal suppresses the outgoing echo and releases listeners/state; the existing
Bloodhound removal listener stops tracking the deleted target.
Unknown UIDs, malformed tokens, duplicates, unsent edits, and ended-session frames
cannot delete unrelated/native or newer points. Outcomes/failures are logged
without sensitive payloads.

The watch removes only its companion-owned map/navigation copy at or before the
revision's original event time, retaining other-source copies and a replay watermark.
For watch-origin points, the original local point is also removed unless a local
edit is pending. Watch received-marker Delete retains its existing UID-wide local
UX, but relays only a selected companion-source point. Bloodhound menu dismissal
is not point deletion. Legacy stale BLE marker clears are no longer imported as
fresh markers; watch point deletion never generates a network clear.

There is no fabricated expiry, network deletion broadcast, ACK, offline replay,
bulk sync, or deletion on disconnect. Transport enqueue is not delivery confirmation.
Update both apps together; older peers can still receive points but have no deletion
authorization token. Validate both directions on paired hardware before deployment.

Validation: `PhoneMarkerRelayTest` exercises the production forwarding policy,
exact envelope/XML preservation, create/update deduplication, filtering,
watch-echo release, disconnect/session rejection, removal/stop state, and write
failures. The production local-removal policy is also exercised with synchronous
removal events to verify that incoming deletion invokes only local removal and
cannot emit a BLE echo. SDK `javap` inspection confirms `MapItem.removeFromGroup`
delegates to local `MapGroup.removeItem`, not a CoT dispatcher/importer. These JVM
tests do not establish ATAK/BLE hardware end-to-end behavior or side effects from
unrelated installed plugins.
Paired-device acceptance should include move/title/detail edits, watch-origin
edits, unsupported PLI/emergency/shape items, disconnect/watch switching, and
plugin stop/start. Specifically, create a phone point and start watch Bloodhound,
delete in ATAK, and verify the companion-source point/target disappears and watch
navigation stops. Delete a shared watch-dropped point on the watch and verify
ATAK marker/tracking removal. Edit that watch-origin point in ATAK and repeat both
directions. A same-UID TAK copy on the watch must survive phone deletion. Replay an
old deletion after a newer edit or reconnect: newer/unshared points must survive,
without echo deletion or startup replay. These are pending hardware acceptance
checks, not claims of device validation. With a third TAK/network peer displaying
the same point, delete on either paired device and verify that third peer retains
its copy and receives no deletion packet.
