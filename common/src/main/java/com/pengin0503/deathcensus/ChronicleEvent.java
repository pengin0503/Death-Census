package com.pengin0503.deathcensus;

import java.util.List;
import java.util.Objects;

/**
 * Persisted world-chronicle row (DESIGN 1.4.0 §3). PR-1 defines only the
 * storage shape; no events are produced or reconstructed yet. Old saves decode
 * to an empty list, never to this no-op sentinel.
 *
 * <p>No coordinates are stored, so a row carries no positional disclosure.
 */
public record ChronicleEvent(
        long epochMillis,
        long gameDay,
        Kind kind,
        String uuid,
        String name,
        String dimension,
        String causeCategory,
        int count,
        boolean reconstructed) {

    /** Kinds provable from retained records or persisted counters (§3). */
    public enum Kind {
        SERVER_FIRST,
        NETHER_FIRST,
        END_DRAGON_DEATH,
        TOTAL_COUNT,
        CATEGORY_COMPLETE
    }

    /** No-op sentinel; absent chronicle data is represented by an empty list. */
    public static final ChronicleEvent EMPTY = new ChronicleEvent(
            0L, 0L, Kind.SERVER_FIRST, "", "", "", "", 0, false);

    public ChronicleEvent {
        kind = kind == null ? Kind.SERVER_FIRST : kind;
        uuid = uuid == null ? "" : uuid;
        name = name == null ? "" : name;
        dimension = dimension == null ? "" : dimension;
        causeCategory = causeCategory == null ? "" : causeCategory;
        count = Math.max(0, count);
    }

    /** True for the no-op sentinel. */
    public boolean isEmpty() {
        return epochMillis == 0L && gameDay == 0L && count == 0 && !reconstructed
                && uuid.isEmpty() && name.isEmpty() && dimension.isEmpty()
                && causeCategory.isEmpty();
    }

    /** Immutable, null-free copy of a stored chronicle list. */
    public static List<ChronicleEvent> copyOf(List<ChronicleEvent> events) {
        return events == null ? List.of() : events.stream().filter(Objects::nonNull).toList();
    }
}
