package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WearTakBleClientIdentityTest {
    @Test
    public void firstIdentityIsAccepted() {
        assertFalse(WearTakBleClient.hasPairedIdentityMismatch(null, "watch-1"));
        assertFalse(WearTakBleClient.hasPairedIdentityMismatch("", "watch-1"));
    }

    @Test
    public void sameIdentityIsAccepted() {
        assertFalse(WearTakBleClient.hasPairedIdentityMismatch("watch-1", "watch-1"));
    }

    @Test
    public void changedIdentityIsRejected() {
        assertTrue(WearTakBleClient.hasPairedIdentityMismatch("watch-1", "watch-2"));
    }
}
