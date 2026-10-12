package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class TraditionalBleReconnectTest {
    private final Scheduler scheduler = new Scheduler();
    private final Transport transport = new Transport();
    private final TraditionalBleReconnect controller = new TraditionalBleReconnect(scheduler, transport);

    private void ready() {
        controller.start(true, true);
        controller.onReady("bonded-watch");
    }

    @Test public void defaultOffDoesNotProbeOrRetryEvenAfterReady() {
        controller.start(false, true);
        controller.onReady("bonded-watch");
        controller.onLoss();
        controller.resume();
        assertTrue(scheduler.pending.isEmpty());
        assertFalse(controller.isRetrying());
    }

    @Test public void initialConnectionFailureCannotArmReconnect() {
        controller.start(true, true);
        controller.onLoss();
        controller.resume();
        assertTrue(scheduler.pending.isEmpty());
    }

    @Test public void unexpectedLossStartsAtTwoSecondsThenSixtySecondsAfterFailure() {
        ready();
        controller.onLoss();
        assertEquals(2000, scheduler.delay());
        scheduler.fire();
        assertEquals("bonded-watch", transport.address);
        assertEquals(30000, scheduler.delay());
        controller.onLoss();
        assertEquals(60000, scheduler.delay());
        scheduler.fire();
        controller.onLoss();
        assertEquals(60000, scheduler.delay());
    }

    @Test public void readinessStopsRetryAndRestartsBoundedHealthProbe() {
        ready();
        controller.onLoss();
        Runnable stale = scheduler.first();
        controller.onReady("new-watch");
        stale.run();
        assertEquals(0, transport.connects);
        assertFalse(controller.isRetrying());
        assertEquals(30000, scheduler.delay());
        scheduler.fire();
        assertEquals(1, transport.probes);
        assertEquals(10000, scheduler.delay());
        controller.onHealthResponse();
        assertEquals(30000, scheduler.delay());
        assertEquals(0, transport.resets);
    }

    @Test public void twoMissedProbesResetAndRetryWithoutContinuousPolling() {
        ready();
        scheduler.fire(); // 30-second probe
        scheduler.fire(); // 10-second timeout
        assertEquals(30000, scheduler.delay());
        scheduler.fire();
        scheduler.fire();
        assertEquals(1, transport.resets);
        assertEquals(2000, scheduler.delay());
    }

    @Test public void failedProbeResetsAndRetries() {
        ready();
        transport.probeAccepted = false;
        scheduler.fire();
        assertEquals(1, transport.resets);
        assertEquals(2000, scheduler.delay());
    }

    @Test public void manualDisconnectAndTrustLossDisarmUntilAnotherReadySession() {
        ready();
        controller.onLoss();
        Runnable old = scheduler.first();
        controller.disarm();
        old.run();
        controller.resume();
        controller.onLoss();
        assertTrue(scheduler.pending.isEmpty());
        assertEquals(0, transport.connects);
        controller.onReady("another-watch");
        controller.onLoss();
        scheduler.fire();
        assertEquals("another-watch", transport.address);
    }

    @Test public void toggleOffCancelsAllTimersAndLateCallbacks() {
        ready();
        Runnable health = scheduler.first();
        controller.setEnabled(false);
        health.run();
        controller.onLoss();
        assertTrue(scheduler.pending.isEmpty());
        assertEquals(0, transport.probes);
        controller.setEnabled(true);
        assertEquals(2000, scheduler.delay());
    }

    @Test public void bluetoothOffPausesAndOnResumesPreviouslyArmedPeer() {
        ready();
        controller.onLoss();
        Runnable old = scheduler.first();
        transport.available = false;
        controller.pause();
        old.run();
        controller.resume();
        assertTrue(scheduler.pending.isEmpty());
        transport.available = true;
        controller.resume();
        assertEquals(2000, scheduler.delay());
        scheduler.fire();
        assertEquals(1, transport.connects);
    }

    @Test public void samsungAndGarminNeverCompeteWithTraditionalRetries() {
        ready();
        controller.onLoss();
        Runnable old = scheduler.first();
        controller.setAllowed(false);
        old.run();
        controller.onReady("samsung-peer");
        controller.onLoss();
        assertTrue(scheduler.pending.isEmpty());
        controller.stop(); // Garmin owns its separate lifecycle.
        controller.resume();
        assertEquals(0, transport.connects);
    }

    @Test public void switchingModesCancelsPendingHealthTimeoutAndRetryCallbacks() {
        ready();
        Runnable staleProbe = scheduler.first();
        scheduler.fire();
        Runnable staleHealthTimeout = scheduler.first();
        controller.setAllowed(false);
        controller.stop();
        staleProbe.run();
        staleHealthTimeout.run();
        assertEquals(1, transport.probes);
        assertEquals(0, transport.resets);
        assertTrue(scheduler.pending.isEmpty());

        controller.start(true, true, "bonded-watch");
        Runnable staleRetry = scheduler.first();
        controller.setAllowed(false);
        controller.stop();
        controller.start(true, false, "bonded-watch"); // Samsung owner only.
        staleRetry.run();
        controller.onLoss();
        controller.setEnabled(true); // Even a stale toggle callback cannot activate another mode.
        controller.resume();
        assertEquals(0, transport.connects);
        assertTrue(scheduler.pending.isEmpty());
    }

    @Test public void repeatedStopStartCannotReuseOldSessionTimersOrTarget() {
        ready();
        controller.onLoss();
        Runnable old = scheduler.first();
        for (int i = 0; i < 3; i++) {
            controller.stop();
            controller.start(true, true);
            old.run();
            controller.resume();
        }
        assertTrue(scheduler.pending.isEmpty());
        assertEquals(0, transport.connects);
    }

    @Test public void reloadCanResumeOnlyExplicitlyEnabledPreviouslyReadyPeer() {
        controller.start(false, true, "previously-ready-peer");
        assertTrue(scheduler.pending.isEmpty());
        controller.stop();
        controller.start(true, false, "previously-ready-peer");
        assertTrue(scheduler.pending.isEmpty());
        controller.stop();
        controller.start(true, true, "previously-ready-peer");
        Runnable stale = scheduler.first();
        controller.stop();
        controller.start(true, true, "previously-ready-peer");
        stale.run();
        assertEquals(0, transport.connects);
        scheduler.fire();
        assertEquals("previously-ready-peer", transport.address);
        assertEquals(1, transport.connects);
    }

    @Test public void failedConnectAndSetupTimeoutKeepSixtySecondBackoff() {
        ready();
        controller.onLoss();
        transport.connectAccepted = false;
        scheduler.fire();
        assertEquals(60000, scheduler.delay());
        transport.connectAccepted = true;
        scheduler.fire();
        assertEquals(30000, scheduler.delay());
        scheduler.fire();
        assertEquals(1, transport.resets);
        assertEquals(60000, scheduler.delay());
    }

    @Test public void busyTransportDefersInsteadOfLaunchingSecondConnection() {
        ready();
        controller.onLoss();
        transport.busy = true;
        scheduler.fire();
        assertEquals(0, transport.connects);
        assertEquals(60000, scheduler.delay());
    }

    private static final class Scheduler implements SystemBondedWatchConnection.Scheduler {
        final Map<Runnable, Long> pending = new LinkedHashMap<>();
        @Override public void execute(Runnable action) { action.run(); }
        @Override public void later(Runnable action, long delay) { pending.put(action, delay); }
        @Override public void cancel(Runnable action) { pending.remove(action); }
        Runnable first() { return pending.keySet().iterator().next(); }
        long delay() { return pending.get(first()); }
        void fire() { Runnable r = first(); pending.remove(r); r.run(); }
    }

    private static final class Transport implements TraditionalBleReconnect.Transport {
        boolean available = true, busy, connectAccepted = true, probeAccepted = true;
        int connects, probes, resets;
        String address;
        @Override public boolean available() { return available; }
        @Override public boolean busy() { return busy; }
        @Override public boolean connect(String address) {
            connects++;
            this.address = address;
            return connectAccepted;
        }
        @Override public boolean probe() { probes++; return probeAccepted; }
        @Override public void reset() { resets++; }
    }
}
