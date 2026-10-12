package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WearTakBleClientTest {
    @Test
    public void emptyOrFirstUseIdentityIsAccepted() {
        assertFalse(WearTakBleClient.hasPairedIdentityMismatch(null, "watch-1"));
        assertFalse(WearTakBleClient.hasPairedIdentityMismatch("", "watch-1"));
        assertFalse(WearTakBleClient.hasPairedIdentityMismatch("watch-1", ""));
    }

    @Test
    public void changedIdentityIsRejected() {
        assertTrue(WearTakBleClient.hasPairedIdentityMismatch("watch-1", "watch-2"));
        assertFalse(WearTakBleClient.hasPairedIdentityMismatch("watch-1", "watch-1"));
    }

    @Test
    public void bondTrustLossReasonsClearPairedIdentity() {
        String[] reasons = {
                "bond_recovery_not_found",
                "bond_recovery_scan_error",
                "bond_lost_before_connect",
                "bond_lost_before_write",
                "bond_lost_during_tx",
                "bond_cancelled",
                "bond_lost"
        };

        for (String reason : reasons) {
            assertTrue(reason, WearTakBleClient.shouldClearPairedIdentity(reason));
        }
    }

    @Test
    public void nonTrustLossReasonDoesNotClearPairedIdentity() {
        assertTrue(WearTakBleClient.shouldClearPairedIdentity("watch_uid_mismatch"));
        assertFalse(WearTakBleClient.shouldClearPairedIdentity("gatt_status_133"));
        assertFalse(WearTakBleClient.shouldClearPairedIdentity(null));
    }
}
