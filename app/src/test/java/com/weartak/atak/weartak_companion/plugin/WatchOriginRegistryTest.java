package com.weartak.atak.weartak_companion.plugin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WatchOriginRegistryTest {
    @Test
    public void markerOriginIsConsumedOnce() {
        WatchOriginRegistry.markMarker("garmin-marker");
        assertTrue(WatchOriginRegistry.isMarker("garmin-marker"));
        assertTrue(WatchOriginRegistry.consumeMarker("garmin-marker"));
        assertFalse(WatchOriginRegistry.consumeMarker("garmin-marker"));
    }

    @Test
    public void chatOriginIsConsumedOnce() {
        WatchOriginRegistry.markChat("garmin-chat");
        assertTrue(WatchOriginRegistry.consumeChat("garmin-chat"));
        assertFalse(WatchOriginRegistry.consumeChat("garmin-chat"));
    }

    @Test
    public void emergencyOriginCanBeClearedAfterCancellation() {
        WatchOriginRegistry.markEmergency("garmin-emergency");
        assertTrue(WatchOriginRegistry.isEmergency("garmin-emergency"));
        WatchOriginRegistry.clearEmergency("garmin-emergency");
        assertFalse(WatchOriginRegistry.isEmergency("garmin-emergency"));
    }
}
