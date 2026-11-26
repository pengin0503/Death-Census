package com.pengin0503.deathcensus;

import java.util.List;
import java.util.Objects;

/**
 * A cancelled death or totem save (DESIGN 1.4.0 §10). PR-1 defines only the
 * storage shape: no hooks are installed yet, and old saves/backups decode to
 * empty lists rather than inventing historical saves.
 *
 * <p>Near deaths are always separate from real death records and totals.
 */
public record NearDeathEvent(
        String uuid,
        String name,
        long epochMillis,
        String dimension,
        double x,
        double y,
        double z,
        String causeCategory,
        String causeId,
        Kind kind) {

    /** The two observable hook kinds (§10); missing values default to CANCELLED. */
    public enum Kind {
        TOTEM,
        CANCELLED
    }

    /** No-op sentinel; absent near-death history is an empty list instead. */
    public static final NearDeathEvent EMPTY = new NearDeathEvent(
            "", "", 0L, "", 0.0, 0.0, 0.0, "", "", Kind.CANCELLED);

    public NearDeathEvent {
        uuid = uuid == null ? "" : uuid;
        name = name == null ? "" : name;
        dimension = dimension == null ? "" : dimension;
        causeCategory = causeCategory == null ? "" : causeCategory;
        causeId = causeId == null ? "" : causeId;
        kind = kind == null ? Kind.CANCELLED : kind;
    }

    /** True for the no-op sentinel. */
    public boolean isEmpty() {
        return epochMillis == 0L && uuid.isEmpty() && name.isEmpty() && dimension.isEmpty()
                && causeCategory.isEmpty() && causeId.isEmpty();
    }

    /** Immutable, null-free copy of a stored near-death list. */
    public static List<NearDeathEvent> copyOf(List<NearDeathEvent> events) {
        return events == null ? List.of() : events.stream().filter(Objects::nonNull).toList();
    }
}
