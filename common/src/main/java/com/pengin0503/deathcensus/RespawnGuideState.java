package com.pengin0503.deathcensus;

import java.util.UUID;

/**
 * Transient, process-local state for one victim's §9 respawn return guide.
 *
 * <p>The seven recorded values mirror the death record that already exists:
 * {@code (uuid, dimension, x, y, z, deathEpochMillis, expiresAtTick)}.
 * Nothing is serialized into {@code SavedData} and no other player's data is
 * referenced. {@code shown}/{@code lastRefreshTick} only throttle the
 * ActionBar after the respawn listener has delivered the first message.
 */
public final class RespawnGuideState {
    private final UUID uuid;
    private final String dimension;
    private final double x;
    private final double y;
    private final double z;
    private final long deathEpochMillis;
    private final long expiresAtTick;

    private boolean shown;
    private long lastRefreshTick = Long.MIN_VALUE;

    public RespawnGuideState(UUID uuid, String dimension, double x, double y, double z,
                             long deathEpochMillis, long expiresAtTick) {
        if (uuid == null) {
            throw new IllegalArgumentException("guide uuid must not be null");
        }
        this.uuid = uuid;
        this.dimension = dimension == null ? "" : dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.deathEpochMillis = deathEpochMillis;
        this.expiresAtTick = expiresAtTick;
    }

    /** Builds state at death time from the current tick and the configured cap. */
    public static RespawnGuideState atDeath(UUID uuid, String dimension, double x, double y,
                                            double z, long deathEpochMillis, long currentTick,
                                            long maxTicks) {
        long lifetime = Math.max(0L, maxTicks);
        long expiry = currentTick > Long.MAX_VALUE - lifetime
                ? Long.MAX_VALUE : currentTick + lifetime;
        return new RespawnGuideState(uuid, dimension, x, y, z, deathEpochMillis, expiry);
    }

    public UUID uuid() {
        return uuid;
    }

    public String dimension() {
        return dimension;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public long deathEpochMillis() {
        return deathEpochMillis;
    }

    public long expiresAtTick() {
        return expiresAtTick;
    }

    /** Despawn ticks still available at {@code currentTick}, never negative. */
    public long remainingTicks(long currentTick) {
        return Math.max(0L, expiresAtTick - currentTick);
    }

    /** True once the lifetime has elapsed; callers then discard the state. */
    public boolean expired(long currentTick) {
        return currentTick >= expiresAtTick;
    }

    /** True when a respawn happened in the dimension where the items lie. */
    public boolean sameDimension(String other) {
        return dimension.equals(other == null ? "" : other);
    }

    public boolean shown() {
        return shown;
    }

    public long lastRefreshTick() {
        return lastRefreshTick;
    }

    /** Marks the first respawn message as delivered (the state stays for refresh). */
    public void markShown(long currentTick) {
        shown = true;
        lastRefreshTick = currentTick;
    }

    /** Records a throttled ActionBar refresh so the next one waits a full interval. */
    public void markRefreshed(long currentTick) {
        lastRefreshTick = currentTick;
    }

    /** True when at least {@code intervalTicks} have passed since the last message. */
    public boolean dueForRefresh(long currentTick, long intervalTicks) {
        if (!shown || currentTick < lastRefreshTick) {
            return false;
        }
        return currentTick - lastRefreshTick >= Math.max(0L, intervalTicks);
    }
}
