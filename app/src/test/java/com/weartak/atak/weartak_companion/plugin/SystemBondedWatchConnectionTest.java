package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.Assert.*;

public class SystemBondedWatchConnectionTest {
    private final FakeScheduler scheduler = new FakeScheduler();
    private final FakeTransport transport = new FakeTransport();
    private String status;
    private int selections;
    private final SystemBondedWatchConnection controller = new SystemBondedWatchConnection(
            scheduler, transport, new SystemBondedWatchConnection.Listener() {
        @Override public void onStatus(String message) { status = message; }
        @Override public void onConnecting(WearTakBleClient.DiscoveredDevice device) { selections++; }
    });

    @Test public void startsOnceAndWaitsForTransportReadiness() {
        controller.start();
        controller.start();
        assertEquals(1, transport.finds);
        transport.found();
        assertEquals(1, transport.connects);
        assertFalse(status.contains("CONNECTED"));
        assertEquals(25000L, scheduler.nextDelay());
        controller.onConnectionChanged(true);
        assertTrue(status.contains("transport ready"));
        assertTrue(scheduler.pending.isEmpty());
    }

    @Test public void stopCancelsDiscoveryAndIgnoresLateFoundCallback() {
        controller.start();
        WearTakBleClient.BondedCompanionListener stale = transport.listener;
        Runnable staleTimeout = scheduler.first();
        controller.stop();
        stale.onFound(transport.device);
        staleTimeout.run();
        controller.onConnectionChanged(true);
        assertEquals(0, transport.connects);
        assertTrue(scheduler.pending.isEmpty());
        assertTrue(status.contains("stopped"));
    }

    @Test public void restartedSessionRejectsPreviousDiscovery() {
        controller.start();
        WearTakBleClient.BondedCompanionListener stale = transport.listener;
        controller.stop();
        controller.start();
        stale.onFound(transport.device);
        stale.onUnavailable("stale failure");
        assertEquals(0, transport.connects);
        transport.found();
        assertEquals(1, transport.connects);
        assertEquals(1, selections);
    }

    @Test public void unavailableRetriesWithCappedExponentialDelay() {
        controller.start();
        long[] expected = {2000, 4000, 8000, 16000, 32000, 60000, 60000};
        for (long delay : expected) {
            transport.listener.onUnavailable("Bluetooth disabled");
            assertTrue(status.contains("Bluetooth disabled"));
            assertEquals(delay, scheduler.nextDelay());
            scheduler.fire();
        }
        assertEquals(expected.length + 1, transport.finds);
    }

    @Test public void discoveryAndSetupTimeoutsResetAndRetry() {
        controller.start();
        assertEquals(35000L, scheduler.nextDelay());
        scheduler.fire();
        assertTrue(status.contains("discovery timed out"));
        assertEquals(2000L, scheduler.nextDelay());
        scheduler.fire();
        transport.found();
        scheduler.fire();
        assertTrue(status.contains("setup timed out"));
        assertEquals(4000L, scheduler.nextDelay());
        assertEquals(2, transport.resets);
    }

    @Test public void disconnectRetriesAndReadinessResetsBackoff() {
        controller.start();
        transport.listener.onUnavailable("Missing BLUETOOTH_CONNECT permission");
        scheduler.fire();
        transport.found();
        controller.onConnectionChanged(true);
        controller.onConnectionChanged(false);
        assertEquals(2000L, scheduler.nextDelay());
        assertTrue(status.contains("disconnected"));
    }

    @Test public void manualStopCancelsRetryAndLateConnectionError() {
        controller.start();
        transport.found();
        controller.onError("GATT connection failed");
        Runnable staleRetry = scheduler.first();
        controller.stop();
        staleRetry.run();
        controller.onError("late error");
        assertEquals(1, transport.finds);
        assertTrue(scheduler.pending.isEmpty());
        assertTrue(status.contains("stopped"));
    }

    @Test public void duplicateFoundAndFailureCannotReplaceConnectingAttempt() {
        controller.start();
        transport.found();
        transport.found();
        transport.listener.onUnavailable("late discovery error");
        assertEquals(1, transport.connects);
        assertEquals(25000L, scheduler.nextDelay());
    }

    @Test public void stopDuringSetupRejectsLateReadyAndTimeout() {
        controller.start();
        transport.found();
        Runnable staleTimeout = scheduler.first();
        controller.stop();
        controller.onConnectionChanged(true);
        staleTimeout.run();
        assertEquals(1, transport.connects);
        assertFalse(controller.isActive());
        assertTrue(scheduler.pending.isEmpty());
        assertTrue(status.contains("stopped"));
    }

    @Test public void bluetoothOffDuringDiscoveryRetriesAndCanRecover() {
        controller.start();
        controller.onError("Bluetooth disabled");
        assertEquals(2000L, scheduler.nextDelay());
        scheduler.fire();
        transport.found();
        controller.onConnectionChanged(true);
        assertTrue(status.contains("transport ready"));
        assertTrue(scheduler.pending.isEmpty());
    }

    @Test public void queuedDiscoveryCallbackCannotReenableStoppedController() {
        scheduler.queueExecution = true;
        controller.start();
        scheduler.flush();
        controller.stop();
        transport.found();
        scheduler.flush();
        assertEquals(0, transport.connects);
        assertFalse(controller.isActive());
        assertTrue(scheduler.pending.isEmpty());
    }

    private static final class FakeScheduler implements SystemBondedWatchConnection.Scheduler {
        final Map<Runnable, Long> pending = new LinkedHashMap<>();
        final Deque<Runnable> executions = new ArrayDeque<>();
        boolean queueExecution;
        @Override public void execute(Runnable action) {
            if (queueExecution) executions.addLast(action);
            else action.run();
        }
        @Override public void later(Runnable action, long delayMs) { pending.put(action, delayMs); }
        @Override public void cancel(Runnable action) { pending.remove(action); }
        Runnable first() { return pending.keySet().iterator().next(); }
        long nextDelay() { return pending.get(first()); }
        void fire() {
            Runnable action = first();
            pending.remove(action);
            action.run();
        }
        void flush() {
            while (!executions.isEmpty()) executions.removeFirst().run();
        }
    }

    private static final class FakeTransport implements SystemBondedWatchConnection.Transport {
        final WearTakBleClient.DiscoveredDevice device =
                new WearTakBleClient.DiscoveredDevice("WearTAK", "AA:BB:CC:DD:EE:FF", 0);
        WearTakBleClient.BondedCompanionListener listener;
        int finds, connects, resets;
        @Override public void find(WearTakBleClient.BondedCompanionListener callback) {
            finds++;
            listener = callback;
        }
        @Override public void connect(WearTakBleClient.DiscoveredDevice device) { connects++; }
        @Override public void reset() { resets++; }
        void found() { listener.onFound(device); }
    }
}
