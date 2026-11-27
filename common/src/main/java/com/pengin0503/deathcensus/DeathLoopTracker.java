package com.pengin0503.deathcensus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Transient, process-local state for the §8 victim-only loop notice. This is
 * the only new hot-path state: one bounded ring of the last
 * {@value #RING_SIZE} qualifying samples per player, plus a bounded set of
 * already-announced {@code (uuid, firstEpoch, count)} keys. Nothing here is
 * written to {@code SavedData}; a restart simply loses pending loops while
 * {@code /dc loops} still reconstructs them from retained records.
 *
 * <p>Version code feeds only non-flagged deaths into {@link #observe}: deaths
 * of opted-out players or of players with {@code hideCoordinates} never enter
 * a ring, so a notice cannot disclose a hidden position. The ringing is O(16)
 * per death with no file I/O and no global snapshot scan.
 */
public final class DeathLoopTracker {
    public static final int RING_SIZE = 16;
    public static final int MAX_ANNOUNCED_KEYS = 1000;
    /** Same bound for the player-keyed rings, so a long-lived server stays bounded. */
    public static final int MAX_TRACKED_PLAYERS = 1000;

    private final Map<String, Deque<DeathLoopSample>> rings = new LinkedHashMap<>();
    private final Set<String> announced = new LinkedHashSet<>();

    /**
     * Records one qualifying death and returns the loops that crossed the
     * threshold on this death and have not been announced before. At most one
     * row is normally returned: the maximal run ending at this sample.
     */
    public List<DeathLoop> observe(DeathLoopSample sample, int radiusBlocks,
                                   long windowMillis, int minDeaths) {
        if (sample == null || !sample.hasPosition()) {
            return List.of();
        }
        Deque<DeathLoopSample> ring = rings.get(sample.uuid());
        if (ring == null) {
            // Access-order map: the least recently used player's ring is evicted first.
            ring = new ArrayDeque<>(RING_SIZE);
            rings.put(sample.uuid(), ring);
            while (rings.size() > MAX_TRACKED_PLAYERS) {
                Iterator<Map.Entry<String, Deque<DeathLoopSample>>> it = rings.entrySet().iterator();
                it.next();
                it.remove();
            }
        }
        ring.addLast(sample);
        while (ring.size() > RING_SIZE) {
            ring.removeFirst();
        }

        List<DeathLoop> fresh = new ArrayList<>();
        for (DeathLoop loop : DeathLoopDetector.findLoopsFromSamples(new ArrayList<>(ring),
                radiusBlocks, windowMillis, minDeaths)) {
            if (loop.lastEpoch() == sample.epochMillis() && shouldAnnounce(loop)) {
                fresh.add(loop);
            }
        }
        while (announced.size() > MAX_ANNOUNCED_KEYS) {
            Iterator<String> it = announced.iterator();
            it.next();
            it.remove();
        }
        return List.copyOf(fresh);
    }

    /** Convenience overload using the §15 defaults. */
    public List<DeathLoop> observe(DeathLoopSample sample) {
        return observe(sample, DeathLoopDetector.DEFAULT_RADIUS_BLOCKS,
                DeathLoopDetector.DEFAULT_WINDOW_MILLIS, DeathLoopDetector.DEFAULT_MIN_DEATHS);
    }

    /**
     * Anti-spam gate: returns true and remembers the key when it is new, false
     * when this exact {@code (uuid, firstEpoch, count)} key was already announced.
     */
    public boolean shouldAnnounce(DeathLoop loop) {
        return loop != null && announced.add(loop.noticeKey());
    }

    /** True when this loop key was already announced since the last reset. */
    public boolean wasAnnounced(DeathLoop loop) {
        return loop != null && announced.contains(loop.noticeKey());
    }

    /** Number of remembered keys; exposed for tests and diagnostics. */
    public int announcedCount() {
        return announced.size();
    }

    /** Number of players with a live ring; exposed for tests and diagnostics. */
    public int ringCount() {
        return rings.size();
    }

    /** Snapshot for tests; the returned deque is independent of internal state. */
    public List<DeathLoopSample> ring(String uuid) {
        Deque<DeathLoopSample> ring = rings.get(uuid == null ? "" : uuid);
        return ring == null ? List.of() : List.copyOf(ring);
    }

    /** Called from {@code resetTransientState()} on stop/world reset. */
    public void clear() {
        rings.clear();
        announced.clear();
    }
}
