package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import static org.junit.Assert.*;

public class GarminEntityForwarderTest {
    private final FakeSource source = new FakeSource();
    private final List<GarminEntityForwarder.Link> targets = new ArrayList<>();
    private final List<Runnable> queue = new ArrayList<>();
    private final RecordingLink first = new RecordingLink();
    private boolean owner = true;
    private final GarminEntityForwarder forwarder = new GarminEntityForwarder(source,
            () -> new ArrayList<>(targets), queue::add, () -> owner);

    private void start() { targets.add(first); forwarder.start(); }
    private void drain() {
        List<Runnable> work = new ArrayList<>(queue);
        queue.clear();
        work.forEach(Runnable::run);
    }
    static Map<String, Object> entity(String uid, String time, String type) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("time", time);
        return GarminEntityAdapter.normalize(uid, 38, -77, type, meta, false, false);
    }

    @Test public void startsAndStopsExactlyOnceAndQueuedCallbacksAreRetired() {
        start();
        forwarder.start();
        assertEquals(1, source.subscriptions);
        source.emit(entity("unit", "2026-01-01T00:00:00Z", "a-f-G"));
        forwarder.close();
        forwarder.close();
        forwarder.start();
        drain();
        assertEquals(1, source.unsubscriptions);
        assertEquals(Collections.singletonList("entities"), first.types);
        source.retained.accept(entity("late", "2026-01-01T00:00:00Z", "a-f-G"));
        drain();
        assertEquals(1, first.types.size());
    }

    @Test public void modeRetirementPreventsEvenQueuedPublicationWithoutClose() {
        start();
        source.emit(entity("unit", "2026-01-01T00:00:00Z", "a-f-G"));
        owner = false;
        drain();
        assertEquals(1, first.types.size());
        assertFalse(forwarder.sendSnapshot(first, 50));
    }

    @Test public void reconnectAndAnotherWatchNeverReceiveOldQueuedUpdates() {
        start();
        source.emit(entity("old", "2026-01-01T00:00:00Z", "a-f-G"));
        first.current = false;
        targets.clear();
        RecordingLink replacement = new RecordingLink();
        targets.add(replacement);
        forwarder.refreshLinks();
        drain();
        assertEquals(Collections.singletonList("entities"), replacement.types);
        source.emit(entity("fresh", "2026-01-02T00:00:00Z", "a-f-G"));
        drain();
        assertEquals("entity", replacement.types.get(1));
        assertEquals(1, first.types.size());
        assertEquals(1, source.subscriptions);
    }

    @Test public void snapshotPrecedesLiveAndDuplicateOrOlderUpdatesAreSuppressed() {
        Map<String, Object> newest = entity("unit", "2026-01-02T00:00:00Z", "a-f-G");
        source.items.add(newest);
        start();
        source.emit(newest);
        source.emit(entity("unit", "2026-01-01T00:00:00Z", "a-f-G"));
        drain();
        assertEquals(Collections.singletonList("entities"), first.types);
        source.emit(entity("unit", "2026-01-03T00:00:00Z", "a-f-G"));
        drain();
        assertEquals("entity", first.types.get(1));
    }

    @Test public void requestSnapshotTargetsOnlyRequestingLinkAndCapsLimit() {
        start();
        RecordingLink other = new RecordingLink();
        targets.add(other);
        forwarder.refreshLinks();
        int firstCount = first.types.size();
        assertTrue(forwarder.sendSnapshot(other, 1000));
        assertEquals(50, source.limit);
        assertEquals(firstCount, first.types.size());
        other.current = false;
        assertFalse(forwarder.sendSnapshot(other, 50));
    }

    @Test public void handoffFailureIsNotCachedAndCanBeRetriedByNextUpdate() {
        start();
        Map<String, Object> unit = entity("unit", "2026-01-01T00:00:00Z", "a-f-G");
        first.accept = false;
        source.emit(unit);
        drain();
        first.accept = true;
        source.emit(unit);
        drain();
        assertEquals(2, first.types.size());
    }

    @Test public void removalDropsPendingPointsButNotExplicitAlertCancellation() {
        start();
        source.emit(entity("point", "2026-01-01T00:00:00Z", "a-f-G"));
        source.removed.accept("point");
        Map<String, Object> cancel = entity("alert", "2026-01-02T00:00:00Z", "b-a-o-tbl-can");
        source.emit(cancel);
        source.removed.accept("alert");
        drain();
        assertEquals(2, first.types.size());
        assertEquals("entity", first.types.get(1));
        assertEquals("CANCEL", first.payloads.get(1).get("state"));
        assertFalse(first.types.contains("marker_delete"));
    }

    @Test public void expiryMetadataAndCancellationStayOrderedAcrossTheSameSource() {
        start();
        Map<String, Object> alert = entity("alert", "2026-01-01T00:00:00Z", "b-a-o-tbl");
        alert.put("stale", "2000-01-01T00:00:00Z");
        source.emit(alert);
        source.emit(entity("alert", "2026-01-02T00:00:00Z", "b-a-o-tbl-can"));
        drain();
        assertEquals("2000-01-01T00:00:00Z", first.payloads.get(1).get("stale"));
        assertEquals("CANCEL", first.payloads.get(2).get("state"));
    }

    @Test public void restartedOwnerGetsFreshSubscriptionAndDedupeState() {
        start();
        forwarder.close();
        GarminEntityForwarder next = new GarminEntityForwarder(source, () -> targets,
                queue::add, () -> owner);
        next.start();
        source.emit(entity("unit", "2026-01-01T00:00:00Z", "a-f-G"));
        drain();
        assertEquals(2, source.subscriptions);
        assertEquals(1, source.unsubscriptions);
        assertEquals("entity", first.types.get(2));
        next.close();
        assertEquals(2, source.unsubscriptions);
    }

    @Test public void actualModeSwitchAndRestartRetireTheObserverAndQueuedWork() {
        targets.add(first);
        GarminEntityForwarder[] active = new GarminEntityForwarder[1];
        ConnectionModeController[] modes = new ConnectionModeController[1];
        modes[0] = new ConnectionModeController(new ConnectionModeController.Transport() {
            @Override public void stop() {
                if (active[0] != null) active[0].close();
                active[0] = null;
            }
            @Override public void start(ConnectionModeController.Mode mode, long token) {
                if (mode != ConnectionModeController.Mode.GARMIN) return;
                active[0] = new GarminEntityForwarder(source, () -> targets, queue::add,
                        () -> modes[0].isCurrent(token));
                active[0].start();
            }
        });
        modes[0].start(ConnectionModeController.Mode.GARMIN);
        Consumer<Map<String, Object>> old = source.retained;
        source.emit(entity("old", "2026-01-01T00:00:00Z", "a-f-G"));
        modes[0].select(ConnectionModeController.Mode.SAMSUNG);
        drain();
        assertEquals(1, first.types.size());
        assertEquals(1, source.unsubscriptions);
        modes[0].select(ConnectionModeController.Mode.GARMIN);
        old.accept(entity("late", "2026-01-01T00:00:00Z", "a-f-G"));
        modes[0].restart();
        drain();
        assertEquals(3, source.subscriptions);
        assertEquals(2, source.unsubscriptions);
        assertEquals(3, first.types.size());
        source.emit(entity("fresh", "2026-01-02T00:00:00Z", "a-f-G"));
        drain();
        assertEquals("entity", first.types.get(3));
        modes[0].stop();
        assertEquals(3, source.unsubscriptions);
    }

    static final class FakeSource implements GarminEntityForwarder.Source {
        Consumer<Map<String, Object>> listener, retained;
        Consumer<String> removed;
        int subscriptions, unsubscriptions, limit;
        final List<Map<String, Object>> items = new ArrayList<>();
        @Override public void subscribe(Consumer<Map<String, Object>> listener, Consumer<String> removed) {
            subscriptions++;
            this.listener = retained = listener;
            this.removed = removed;
        }
        @Override public void unsubscribe() { unsubscriptions++; listener = null; }
        @Override public List<Map<String, Object>> snapshot(int requested) {
            limit = Math.max(1, Math.min(requested, 50));
            return GarminEntityAdapter.nearest(items, 38, -77, limit);
        }
        void emit(Map<String, Object> entity) { if (listener != null) listener.accept(entity); }
    }

    static final class RecordingLink implements GarminEntityForwarder.Link {
        boolean current = true, accept = true;
        final List<String> types = new ArrayList<>();
        final List<Map<String, Object>> payloads = new ArrayList<>();
        @Override public boolean isCurrent() { return current; }
        @Override public boolean send(String type, Map<String, Object> payload) {
            if (!current || !accept) return false;
            types.add(type);
            payloads.add(payload);
            return true;
        }
    }
}
