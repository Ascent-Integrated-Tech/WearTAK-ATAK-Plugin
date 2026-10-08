package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class ConnectionModeControllerTest {
    private static class Route implements ConnectionModeController.Transport {
        final List<String> events = new ArrayList<>();
        long token;
        ConnectionModeController owner;
        @Override public void stop() {
            if (owner != null) assertFalse(owner.isCurrent(token));
            events.add("stop");
        }
        @Override public void start(ConnectionModeController.Mode mode, long token) {
            this.token = token;
            events.add(mode.value);
            assertTrue(owner.isCurrent(token));
        }
    }

    @Test public void restoresSavedModesAndMigratesOnlyAbsentPreference() {
        for (ConnectionModeController.Mode mode : ConnectionModeController.Mode.values()) {
            assertEquals(mode, ConnectionModeController.restore(mode.value, false));
        }
        assertEquals(ConnectionModeController.Mode.SAMSUNG, ConnectionModeController.restore(null, true));
        assertEquals(ConnectionModeController.Mode.TRADITIONAL, ConnectionModeController.restore(null, false));
        assertEquals(ConnectionModeController.Mode.TRADITIONAL, ConnectionModeController.restore("unknown", true));
        assertEquals(ConnectionModeController.Mode.TRADITIONAL, ConnectionModeController.restore("traditional_ble", true));
    }

    @Test public void everyModePairStopsBeforeStartingAndRejectsOldCallbacks() {
        for (ConnectionModeController.Mode from : ConnectionModeController.Mode.values()) {
            for (ConnectionModeController.Mode to : ConnectionModeController.Mode.values()) {
                Route route = new Route();
                route.owner = new ConnectionModeController(route);
                route.owner.start(from);
                long old = route.token;
                route.events.clear();
                route.owner.select(to);
                if (from == to) {
                    assertTrue(route.events.isEmpty());
                    assertTrue(route.owner.isCurrent(old));
                } else {
                    assertEquals("stop", route.events.get(0));
                    assertEquals(to.value, route.events.get(1));
                    assertFalse(route.owner.isCurrent(old));
                }
            }
        }
    }

    @Test public void stopPreservesSelectionWithoutRestartingAndRetryUsesNewToken() {
        Route route = new Route();
        route.owner = new ConnectionModeController(route);
        route.owner.start(ConnectionModeController.Mode.GARMIN);
        long old = route.token;
        route.owner.stop();
        assertEquals(ConnectionModeController.Mode.GARMIN, route.owner.getMode());
        assertFalse(route.owner.isCurrent(old));
        route.owner.start(route.owner.getMode());
        assertFalse(route.owner.isCurrent(old));
        long restarted = route.token;
        route.events.clear();
        route.owner.restart();
        assertEquals(2, route.events.size());
        assertEquals("stop", route.events.get(0));
        assertEquals(ConnectionModeController.Mode.GARMIN.value, route.events.get(1));
        assertFalse(route.owner.isCurrent(restarted));
        assertTrue(route.owner.isCurrent(route.token));
    }
}
