package com.weartak.atak.weartak_companion.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ReconnectPolicyTest {
    @Test
    public void retryDelayUsesBoundedBackoff() {
        assertEquals(2_000L, ReconnectPolicy.delayForAttempt(0));
        assertEquals(60_000L, ReconnectPolicy.delayForAttempt(1));
        assertEquals(120_000L, ReconnectPolicy.delayForAttempt(2));
        assertEquals(900_000L, ReconnectPolicy.delayForAttempt(5));
        assertEquals(900_000L, ReconnectPolicy.delayForAttempt(20));
    }

    @Test
    public void deviceNameNormalizationUsesOneIdentityForm() {
        assertEquals("9514", ReconnectPolicy.normalizeDeviceName("WT-9514"));
        assertEquals("9514", ReconnectPolicy.normalizeDeviceName("WEAROS-9514"));
        assertNull(ReconnectPolicy.normalizeDeviceName("  "));
    }
}
