package com.pengin0503.deathcensus;

import java.util.Objects;

/**
 * Immutable reference to a player who was present at a death (DESIGN 1.4.0 §1).
 *
 * <p>PR-1 only defines the storage shape. Nothing captures witnesses yet, so
 * every existing and old record carries {@link #EMPTY} semantics: an empty list
 * on the {@code DeathEntry}, never a fabricated reference.
 */
public record WitnessRef(String uuid, String nameAtDeath) {
    /** No-op reference used only by tests/placeholders; stored lists are empty instead. */
    public static final WitnessRef EMPTY = new WitnessRef("", "");

    public WitnessRef {
        uuid = uuid == null ? "" : uuid;
        nameAtDeath = nameAtDeath == null ? "" : nameAtDeath;
    }

    /** True for the no-op reference; real references always carry a UUID. */
    public boolean isEmpty() {
        return uuid.isEmpty() && nameAtDeath.isEmpty();
    }

    /** Immutable, null-free copy of a stored witness list. */
    public static java.util.List<WitnessRef> copyOf(java.util.List<WitnessRef> refs) {
        return refs == null ? java.util.List.of()
                : refs.stream().filter(Objects::nonNull).toList();
    }
}
