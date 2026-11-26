package com.pengin0503.deathcensus.ui;

import com.pengin0503.deathcensus.DeathEntry;
import com.pengin0503.deathcensus.PlayerDeaths;

import java.util.List;
import java.util.UUID;

/** Shared fixtures for the common UI tests. */
final class TestData {
    static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private TestData() {
    }

    static DeathEntry death(UUID uuid, String name, long epoch, String category, String dimension, String message) {
        return new DeathEntry(
                uuid.toString(), name, epoch, 1L, 100, 100, dimension,
                1.0, 2.0, 3.0, category, "minecraft:" + category, message,
                "", "", "survival", 5, "last words", "excuse", 2
        );
    }

    static DeathEntry death(UUID uuid, String name, long epoch, String category) {
        return death(uuid, name, epoch, category, "minecraft:overworld", name + " died in " + category);
    }

    static PlayerDeaths player(UUID uuid, String name, boolean optedOut, DeathEntry... deaths) {
        return new PlayerDeaths(uuid.toString(), name, optedOut, List.of(deaths));
    }
}
