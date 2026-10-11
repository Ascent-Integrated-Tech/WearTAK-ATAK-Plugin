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

**Deletion limitation:** ATAK 5.5.1.8 SDK inspection shows that local
`MapItemDeleteReceiver` calls `removeFromGroup()`. `CotEventFactory` does not
turn removed items into expired events; its default marker exporter creates
fresh timestamps. ATAK's remote deletion importer instead accepts a
`t-x-d-d` task with a target `<link>` and optional `__forcedelete`, which is not
the watch's `phone_marker_cot` point contract. Consequently local removal
detaches listeners and drops companion-side relay state, but sends no deletion
packet. No fabricated expiry or unsupported delete envelope is emitted.
The watch can consume genuinely expired valid marker CoT according to its
existing source-scoped stale-removal rules, but this companion does not invent
such an event from a local delete. Explicit phone deletion requires a separately
agreed protocol change. Disconnect deliberately does not delete watch points.

Validation: `PhoneMarkerRelayTest` exercises the production forwarding policy,
exact envelope/XML preservation, create/update deduplication, filtering,
watch-echo release, disconnect/session rejection, removal/stop state, and write
failures. These JVM tests do not establish ATAK/BLE hardware end-to-end behavior.
Paired-device acceptance should include move/title/detail edits, watch-origin
edits, unsupported PLI/emergency/shape items, disconnect/watch switching, and
plugin stop/start.
