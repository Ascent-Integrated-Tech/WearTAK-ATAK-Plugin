package com.weartak.atak.weartak_companion.plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

/** One map subscription, with independent bounded handoff state for each active watch link. */
final class GarminEntityForwarder {
    interface Source {
        void subscribe(Consumer<Map<String, Object>> listener, Consumer<String> removed);
        void unsubscribe();
        List<Map<String, Object>> snapshot(int limit);
    }
    interface Link {
        boolean isCurrent();
        boolean send(String type, Map<String, Object> payload);
    }
    private final Source source;
    private final Supplier<List<Link>> targets;
    private final Consumer<Runnable> schedule;
    private final BooleanSupplier current;
    private final Map<Link, Map<String, Map<String, Object>>> published = new HashMap<>();
    private static final class Pending {
        final boolean alert;
        Pending(Map<String, Object> entity) { alert = Boolean.TRUE.equals(entity.get("isAlert")); }
    }
    private final Map<String, Set<Pending>> pending = new HashMap<>();
    private boolean started;
    private boolean closed;
    private int snapshotSize;

    GarminEntityForwarder(Source source, Supplier<List<Link>> targets,
            Consumer<Runnable> schedule, BooleanSupplier current) {
        this.source = source;
        this.targets = targets;
        this.schedule = schedule;
        this.current = current;
    }

    synchronized void start() {
        if (started || closed || !current.getAsBoolean()) return;
        started = true;
        source.subscribe(this::changed, this::removed);
        refreshLinks();
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        if (started) source.unsubscribe();
        started = false;
        published.clear();
        pending.clear();
    }

    synchronized void refreshLinks() {
        if (!active()) return;
        List<Link> links = targets.get();
        published.keySet().removeIf(link -> !link.isCurrent() || !links.contains(link));
        for (Link link : links) {
            if (!link.isCurrent() || published.containsKey(link)) continue;
            published.put(link, new LinkedHashMap<String, Map<String, Object>>(64, .75f, true) {
                @Override protected boolean removeEldestEntry(
                        Map.Entry<String, Map<String, Object>> eldest) {
                    return size() > 50;
                }
            });
            sendSnapshot(link, 50);
        }
    }

    synchronized boolean sendSnapshot(Link link, int limit) {
        if (!active() || link == null || !link.isCurrent()) return false;
        List<Map<String, Object>> snapshot = source.snapshot(Math.max(1, Math.min(limit, 50)));
        snapshotSize = snapshot.size();
        Map<String, Object> payload = new HashMap<>();
        payload.put("entities", snapshot);
        if (!link.send("entities", payload)) return false;
        Map<String, Map<String, Object>> cache = published.get(link);
        if (cache != null) {
            for (Map<String, Object> entity : snapshot) cache.put((String) entity.get("uid"), entity);
        }
        return true;
    }

    synchronized int snapshotSize() { return snapshotSize; }

    private synchronized void changed(Map<String, Object> entity) {
        if (!active() || entity == null) return;
        List<Link> captured = new ArrayList<>(targets.get());
        if (captured.isEmpty()) return;
        Pending revision = new Pending(entity);
        pending.computeIfAbsent((String) entity.get("uid"), uid -> new HashSet<>()).add(revision);
        schedule.accept(() -> publish(entity, captured, revision));
    }

    private synchronized void removed(String uid) {
        Set<Pending> queued = pending.get(uid);
        if (queued != null) {
            queued.removeIf(revision -> !revision.alert);
            if (queued.isEmpty()) pending.remove(uid);
        }
        for (Map<String, Map<String, Object>> cache : published.values()) cache.remove(uid);
    }

    private synchronized void publish(Map<String, Object> entity, List<Link> captured, Pending revision) {
        String uid = (String) entity.get("uid");
        Set<Pending> queued = pending.get(uid);
        if (!active() || queued == null || !queued.remove(revision)) return;
        if (queued.isEmpty()) pending.remove(uid);
        for (Link link : captured) {
            if (!link.isCurrent()) continue;
            Map<String, Map<String, Object>> cache = published.get(link);
            if (cache == null) continue;
            Map<String, Object> previous = cache.get(uid);
            if (entity.equals(previous) || older(entity, previous)) continue;
            // Acceptance means SDK handoff only, never an acknowledgement from the watch.
            if (link.send("entity", new HashMap<>(entity))) cache.put(uid, entity);
        }
    }

    private static boolean older(Map<String, Object> next, Map<String, Object> previous) {
        if (previous == null || !(next.get("time") instanceof String)
                || !(previous.get("time") instanceof String)) return false;
        try {
            int order = java.time.Instant.parse((String) next.get("time"))
                    .compareTo(java.time.Instant.parse((String) previous.get("time")));
            return order < 0 || order == 0 && Boolean.FALSE.equals(previous.get("active"))
                    && Boolean.TRUE.equals(next.get("active"));
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    private boolean active() { return started && !closed && current.getAsBoolean(); }
}
