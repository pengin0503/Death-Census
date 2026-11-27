package com.pengin0503.deathcensus;

/**
 * Small, immutable tuple shared by the full-snapshot query and the bounded
 * per-player ring used on the death hot path (DESIGN 1.4.0 §8). Version code
 * builds these from a freshly recorded {@link DeathEntry}; the detector and
 * tracker see only these values, so both paths run the identical rule.
 */
public record DeathLoopSample(String uuid, String name, long epochMillis,
                              double x, double y, double z,
                              String dimension, String causeCategory) {
    public DeathLoopSample {
        uuid = uuid == null ? "" : uuid;
        name = name == null ? "" : name;
        dimension = dimension == null ? "" : dimension;
        causeCategory = causeCategory == null ? "" : causeCategory;
    }

    /** Converts a retained record; y is carried for parity with the ring tuple. */
    public static DeathLoopSample of(String uuid, String name, DeathEntry entry) {
        if (entry == null) {
            return null;
        }
        return new DeathLoopSample(uuid == null ? entry.playerUuid() : uuid,
                name == null ? entry.playerName() : name,
                entry.epochMillis(), entry.x(), entry.y(), entry.z(),
                entry.dimension(), entry.causeCategory());
    }

    /** True when the tuple can participate in positional detection. */
    public boolean hasPosition() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }
}
