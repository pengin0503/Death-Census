package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PR-1: pure common-side storage seam, compatibility and placeholder shapes. */
class StorageSeamTest {
    private static DeathEntry oldStyleDeath() {
        return new DeathEntry("u", "Steve", 1000L, 1L, 10, 9, "minecraft:overworld",
                1.0, 2.0, 3.0, "fall", "minecraft:fall", "Steve fell", "", "",
                "survival", 5, "", "", 0);
    }

    @Test
    void twentyArgumentConstructorDefaultsWitnessesAndContext() {
        DeathEntry entry = oldStyleDeath();
        assertTrue(entry.witnesses().isEmpty());
        assertSame(DeathContext.EMPTY, entry.context());
        assertEquals("Steve", entry.playerName());
        assertEquals(5, entry.experienceLevel());

        DeathEntry explicit = new DeathEntry("u", "Steve", 1000L, 1L, 10, 9,
                "minecraft:overworld", 1.0, 2.0, 3.0, "fall", "minecraft:fall",
                "Steve fell", "", "", "survival", 5, "", "", 0,
                List.of(), DeathContext.EMPTY);
        assertEquals(explicit, entry);
    }

    @Test
    void newComponentsNormalizeNullsAndCopyLists() {
        List<WitnessRef> refs = new java.util.ArrayList<>(List.of(new WitnessRef("w", "Alex")));
        DeathEntry entry = new DeathEntry("u", "Steve", 1000L, 1L, 10, 9,
                "minecraft:overworld", 1.0, 2.0, 3.0, "fall", "minecraft:fall",
                "Steve fell", "", "", "survival", 5, "", "", 0,
                refs, new DeathContext(null, null, List.of("minecraft:speed:1"), null, null));
        refs.clear();
        assertEquals(List.of(new WitnessRef("w", "Alex")), entry.witnesses());
        assertEquals("", entry.context().mainHand());
        assertTrue(entry.context().armor().isEmpty());
        assertEquals(List.of("minecraft:speed:1"), entry.context().effects());
        assertEquals("", entry.context().biome());
        assertEquals("", entry.context().weather());

        DeathEntry nullLists = new DeathEntry("u", "Steve", 1000L, 1L, 10, 9,
                "dim", 1.0, 2.0, 3.0, "cat", "id", "msg", "", "", "mode", 1, "", "", 0,
                null, null);
        assertTrue(nullLists.witnesses().isEmpty());
        assertSame(DeathContext.EMPTY, nullLists.context());
    }

    @Test
    void placeholderEmptyFormsHaveTheSpecifiedShapes() {
        assertEquals(new WitnessRef("", ""), WitnessRef.EMPTY);
        assertTrue(WitnessRef.EMPTY.isEmpty());
        assertEquals(new DeathContext("", List.of(), List.of(), "", ""), DeathContext.EMPTY);
        assertTrue(DeathContext.EMPTY.isEmpty());

        assertEquals(new ChronicleEvent(0L, 0L, ChronicleEvent.Kind.SERVER_FIRST,
                "", "", "", "", 0, false), ChronicleEvent.EMPTY);
        assertEquals(List.of("SERVER_FIRST", "NETHER_FIRST", "END_DRAGON_DEATH",
                        "TOTAL_COUNT", "CATEGORY_COMPLETE"),
                java.util.Arrays.stream(ChronicleEvent.Kind.values())
                        .map(Enum::name).toList());

        assertEquals(new NearDeathEvent("", "", 0L, "", 0.0, 0.0, 0.0,
                "", "", NearDeathEvent.Kind.CANCELLED), NearDeathEvent.EMPTY);
        assertEquals(List.of("TOTEM", "CANCELLED"),
                java.util.Arrays.stream(NearDeathEvent.Kind.values())
                        .map(Enum::name).toList());

        // Null components normalize instead of leaking a null into storage.
        ChronicleEvent damaged = new ChronicleEvent(1L, 1L, null, null, null, null, null, -1, true);
        assertSame(ChronicleEvent.Kind.SERVER_FIRST, damaged.kind());
        assertEquals("", damaged.uuid());
        assertEquals(0, damaged.count());
        NearDeathEvent near = new NearDeathEvent(null, null, 1L, null, 0, 0, 0, null, null, null);
        assertEquals("", near.uuid());
        assertSame(NearDeathEvent.Kind.CANCELLED, near.kind());
    }

    @Test
    void playerDeathsCompatibilityConstructorsDefaultNearDeathsToEmpty() {
        DeathEntry death = oldStyleDeath();
        assertTrue(new PlayerDeaths("u", "Steve", false, List.of(death)).nearDeaths().isEmpty());
        assertTrue(new PlayerDeaths("u", "Steve", false, false, false, false,
                List.of(death)).nearDeaths().isEmpty());
        assertTrue(new PlayerDeaths("u", "Steve", false, false, false, false,
                List.of(death), 3, Map.of("fall", 3)).nearDeaths().isEmpty());

        NearDeathEvent near = new NearDeathEvent("u", "Steve", 2L, "dim", 1, 2, 3,
                "fall", "minecraft:fall", NearDeathEvent.Kind.TOTEM);
        PlayerDeaths full = new PlayerDeaths("u", "Steve", false, false, false, false,
                List.of(death), 0, Map.of(), List.of(near));
        assertEquals(List.of(near), full.nearDeaths());
        assertEquals(3, new PlayerDeaths("u", "S", false, false, false, false,
                List.of(death), 3, Map.of("fall", 3)).vanillaDeathsBaseline());
    }

    @Test
    void backupJsonCarriesNewFieldsAndPublicExportShowsWitnessesOnly() {
        DeathEntry death = new DeathEntry("u", "Steve", 1000L, 1L, 10, 9,
                "minecraft:overworld", 1.0, 2.0, 3.0, "fall", "minecraft:fall",
                "Steve fell", "", "", "survival", 5, "", "", 0,
                List.of(new WitnessRef("w", "Alex")),
                new DeathContext("minecraft:sword", List.of("minecraft:helmet"),
                        List.of("minecraft:speed:1"), "minecraft:plains", "clear"));
        NearDeathEvent near = new NearDeathEvent("u", "Steve", 2L, "minecraft:overworld",
                1, 2, 3, "fall", "minecraft:fall", NearDeathEvent.Kind.TOTEM);
        ChronicleEvent chronicle = new ChronicleEvent(1000L, 1L, ChronicleEvent.Kind.SERVER_FIRST,
                "u", "Steve", "minecraft:overworld", "fall", 1, false);
        PlayerDeaths pd = new PlayerDeaths("u", "Steve", false, false, false, false,
                List.of(death), 0, Map.of(), List.of(near));

        String backup = ExportRenderers.toBackupJson(List.of(pd), 2000L, List.of(chronicle));
        assertTrue(backup.contains("\"witnesses\""));
        assertTrue(backup.contains("\"nameAtDeath\": \"Alex\""));
        assertTrue(backup.contains("\"mainHand\": \"minecraft:sword\""));
        assertTrue(backup.contains("\"nearDeaths\""));
        assertTrue(backup.contains("\"chronicleEvents\""));
        assertTrue(backup.contains("\"reconstructed\": false"));

        String publicJson = ExportRenderers.toJson(List.of(pd), 2000L);
        // PR-2 adds public witness names; PR-3 adds public context. Near-death
        // and chronicle aggregates stay backup-only.
        assertTrue(publicJson.contains("\"witnesses\": [\"Alex\"]"));
        assertTrue(publicJson.contains("\"mainHand\": \"minecraft:sword\""));
        assertFalse(publicJson.contains("nearDeaths"));
        assertFalse(publicJson.contains("chronicleEvents"));
        assertEquals(publicJson, ExportRenderers.toJson(List.of(pd), 2000L, false));
    }

    @Test
    void redactionCopiesPreserveAdditiveComponents() {
        DeathEntry death = new DeathEntry("u", "Steve", 1000L, 1L, 10, 9,
                "dim", 1.0, 2.0, 3.0, "cat", "id", "msg", "", "", "mode", 1, "", "", 0,
                List.of(new WitnessRef("w", "Alex")),
                new DeathContext("main", List.of("armor"), List.of("effect"), "biome", "clear"));
        PlayerDeaths pd = new PlayerDeaths("u", "Steve", false, true, false, false,
                List.of(death));

        DeathEntry redacted = DeathRedactor.apply(death, PlayerPrivacy.of(pd), false);
        assertEquals(death.witnesses(), redacted.witnesses());
        // hideCoordinates is set: items/effects/weather survive, biome is hidden.
        assertEquals(death.context().withBiome(DeathRedactor.COORDINATE_MARKER), redacted.context());
        assertEquals("biome", death.context().biome());
        assertTrue(Double.isNaN(redacted.x()));
        assertEquals(death.x(), death.x()); // original untouched

        // Player-level near-death history is carried by the unredacted snapshot
        // (used by backups), so the public render copy keeps the 1.3.0 shape.
        NearDeathEvent near = new NearDeathEvent("u", "Steve", 2L, "dim", 1, 2, 3,
                "cat", "id", NearDeathEvent.Kind.TOTEM);
        PlayerDeaths withNear = new PlayerDeaths("u", "Steve", false, false, false, false,
                List.of(death), 0, Map.of(), List.of(near));
        assertEquals(List.of(near), withNear.nearDeaths());
        assertTrue(DeathRedactor.publicView(withNear).nearDeaths().isEmpty());
    }
}
