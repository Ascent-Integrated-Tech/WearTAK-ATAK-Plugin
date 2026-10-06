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

_________________________________________________________________
DEVELOPER NOTES

SAMSUNG SYSTEM-BONDED WATCH TEST BRANCH

On feature/weartak-samsung-system-pairing, starting the Companion plugin
automatically discovers and connects an existing Android system-bonded
WearTAK watch. Enable Bluetooth, grant Nearby Devices permissions to the
ATAK host, and pair the Samsung watch through Android/Galaxy Wearable first.
Run the WearTAK watch app so its A11A BLE service is available.

Discovery uses the previously identified WearTAK address if still bonded;
otherwise it scans for bonded devices advertising WearTAK's A11A service.
A Samsung device name alone is never sufficient. This automatic route
never creates or removes a bond. The original Scan/manual Connect route
is retained and may request a new BLE bond for an unbonded selection.

CONNECTED, the connected card, and settings requests are enabled only
after service discovery and successful A11B notification subscription
(CCCD write). Discovery is bounded to 35 seconds and GATT setup to
25 seconds. Failures/disconnects retry at 2, 4, 8, 16, 32, then at most
60-second intervals; successful readiness resets the backoff. Bluetooth
disabled and missing permissions are reported, and periodic retries recover
after Bluetooth/permissions are restored. This does not request permissions
on the user's behalf.

Scan, manual Connect, and deliberate Disconnect stop automatic discovery,
setup, and retries for the current plugin lifecycle. Disconnect does not
immediately reconnect or forget the system bond. Restarting the plugin
reenables automatic connection on this standalone test branch.
SystemBondedWatchConnection.start()/stop() are the explicit activation seam:
a future connection-mode selector must stop this controller before activating
another transport and start it only when System Bonded Watch is selected.
No selector, Garmin transport, or other feature branches are included here.

For end-to-end bond preservation use the corresponding WearOS Samsung
system-pairing watch version (wearos-tak-civ-samsung-system-pairing,
commit 9a90f83 or its successors). A TPP-signed Companion plugin cannot
update or replace the watch app. The Companion source ZIP is for TPP
build/signing and testing, not certification or a directly installable APK.
Build configuration intentionally retains ATAK target 5.5.1; behavior on
the requested ATAK 5.8 phone is not proven by local compilation/unit tests.
TPP must provide its normal TAK SDK/build credentials; no local SDK,
local.properties, generated outputs, or signing key is included.
