package com.weartak.atak.weartak_companion.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BleCotBridgeEmergencyPolicyTest {
    @Test
    public void rejectsNewGarminEmergencyWithoutPhoneFix() {
        assertFalse(BleCotBridge.shouldDispatchGarminEmergency("ALERT", false));
    }

    @Test
    public void permitsGarminEmergencyWithPhoneFix() {
        assertTrue(BleCotBridge.shouldDispatchGarminEmergency("ALERT", true));
    }

    @Test
    public void permitsCancellationWithoutPhoneFix() {
        assertTrue(BleCotBridge.shouldDispatchGarminEmergency("CANCEL", false));
    }

    @Test
    public void usesPhoneCallsignWhenCancellationHasNoCallsign() {
        assertEquals("PHONE-1", BleCotBridge.resolveEmergencyCallsign("", "PHONE-1"));
        assertEquals("PHONE-1", BleCotBridge.resolveEmergencyCallsign(null, "PHONE-1"));
        assertEquals("WATCH-1", BleCotBridge.resolveEmergencyCallsign("WATCH-1", "PHONE-1"));
    }
}
