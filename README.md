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

_________________________________________________________________
DEVELOPER NOTES

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
