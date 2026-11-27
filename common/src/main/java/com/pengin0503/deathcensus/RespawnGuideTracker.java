package com.pengin0503.deathcensus;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Transient, bounded {@code Map<UUID, GuideState>} for DESIGN 1.4.0 §9.
 *
 * <p>The map is process-local and keyed by the victim's UUID. A version tree
 * only ever looks up the UUID of the player it is about to message; there is no
 * broadcast, no snapshot view and no persisted field. A new death replaces the
 * previous state (the §9 "second death" stop condition), logout removes just
 * that player's state, and {@link #clear()} is called from
 * {@code DeathTracker.resetTransientState()} on server stop.
 */
public final class RespawnGuideTracker {
    /** Extra bound so a long-lived server cannot accumulate unbounded guides. */
    public static final int MAX_TRACKED_PLAYERS = 1000;

    // Access-order: the least recently used victim is evicted at the cap.
    private final Map<UUID, RespawnGuideState> guides = new LinkedHashMap<>();

    /**
     * Writes the guide for a fresh death, replacing any earlier guide for that
     * player. Returns the new state, or {@code null} for a null UUID.
     */
    public RespawnGuideState begin(UUID uuid, String dimension, double x, double y, double z,
                                   long deathEpochMillis, long currentTick, long maxTicks) {
        if (uuid == null) {
            return null;
        }
        RespawnGuideState state = RespawnGuideState.atDeath(uuid, dimension, x, y, z,
                deathEpochMillis, currentTick, maxTicks);
        guides.put(uuid, state);
        while (guides.size() > MAX_TRACKED_PLAYERS) {
            Iterator<UUID> it = guides.keySet().iterator();
            it.next();
            it.remove();
        }
        return state;
    }

    /** Self-only lookup: a caller gets a state only for the UUID it asks about. */
    public RespawnGuideState get(UUID uuid) {
        return uuid == null ? null : guides.get(uuid);
    }

    /** Removes one player's state (respawn completion, logout or timeout). */
    public RespawnGuideState remove(UUID uuid) {
        return uuid == null ? null : guides.remove(uuid);
    }

    /** Drops all states whose lifetime has elapsed; returns the number removed. */
    public int pruneExpired(long currentTick) {
        int before = guides.size();
        guides.values().removeIf(state -> state.expired(currentTick));
        return before - guides.size();
    }

    /** Live state count; exposed for tests and diagnostics only. */
    public int size() {
        return guides.size();
    }

    /** Called from {@code resetTransientState()} on stop/world reset. */
    public void clear() {
        guides.clear();
    }
}
