package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;
import com.mojang.serialization.DynamicOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PR-10 (DESIGN 1.3.0 §9): lifetime baselines, max/merge idempotency, no fabricated records. */
class VanillaBaselineTest {
    private static final UUID UUID_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID UNKNOWN = UUID.fromString("00000000-0000-0000-0000-000000000099");

    private static DeathEntry death(UUID uuid, long epoch) {
        return new DeathEntry(uuid.toString(), "Alex", epoch, 3L, 500, 400,
                "minecraft:overworld", 1.0, 64.0, 2.0, "fall", "minecraft:fall",
                "Alex fell", "", "", "survival", 5, "", "", 0);
    }

    private static CensusData withPlayer(UUID uuid, String name) {
        CensusData data = new CensusData();
        data.player(uuid).lastKnownName = name;
        return data;
    }

    private static VanillaStats stats(int deaths, Map<String, Integer> killedBy) {
        return new VanillaStats(deaths, killedBy);
    }

    @Test
    void importSetsBaselineOnlyAndNeverFabricatesRecords() {
        CensusData data = withPlayer(UUID_A, "Alex");
        VanillaImportResult result = data.importVanillaBaselines(
                Map.of(UUID_A, stats(12, Map.of("minecraft:player", 4))), false);
        assertEquals(1, result.rows().size());
        assertEquals("Alex (00000000-0000-0000-0000-000000000001)",
                result.rows().get(0).name() + " (" + result.rows().get(0).uuid() + ")");
        assertEquals(12, result.rows().get(0).vanillaDeaths());
        assertEquals(12, result.rows().get(0).baselineDeaths());
        assertEquals("player", result.rows().get(0).topCause());
        assertEquals(0, result.skippedPlayers());
        assertEquals(12L, result.addedDeaths());
        assertEquals(12, data.player(UUID_A).vanillaDeathsBaseline);
        assertEquals(Map.of("player", 4), data.player(UUID_A).vanillaBaselineCauses);
        assertEquals(12L, data.vanillaBaselineTotal());
        assertEquals(12L, data.lifetimeDeaths());
        assertEquals(0L, data.storedLifetimeDeaths(), "the stored counter must stay raw");
        assertTrue(data.player(UUID_A).deaths.isEmpty(), "no synthetic DeathEntry may be created");
        assertTrue(data.player(UUID_A).lifetimeCategories.isEmpty());
    }

    @Test
    void doubleImportAndSmallerStatsNeverDoubleCountOrDecrease() {
        CensusData data = withPlayer(UUID_A, "Alex");
        VanillaImportResult first = data.importVanillaBaselines(Map.of(UUID_A, stats(10, Map.of())), false);
        assertEquals(10L, first.addedDeaths());
        VanillaImportResult again = data.importVanillaBaselines(Map.of(UUID_A, stats(10, Map.of())), false);
        assertEquals(0L, again.addedDeaths());
        assertEquals(10, data.player(UUID_A).vanillaDeathsBaseline);
        assertEquals(10L, data.lifetimeDeaths());

        VanillaImportResult smaller = data.importVanillaBaselines(Map.of(UUID_A, stats(6, Map.of())), false);
        assertEquals(0L, smaller.addedDeaths());
        assertEquals(10, data.player(UUID_A).vanillaDeathsBaseline);

        VanillaImportResult bigger = data.importVanillaBaselines(Map.of(UUID_A, stats(14, Map.of())), false);
        assertEquals(4L, bigger.addedDeaths());
        assertEquals(14, data.player(UUID_A).vanillaDeathsBaseline);
        assertEquals(14L, data.lifetimeDeaths());
    }

    @Test
    void importAfterRealDeathsSubtractsThemFromTheVanillaTotal() {
        CensusData data = withPlayer(UUID_A, "Alex");
        data.recordDeath(death(UUID_A, 100L), 100);
        data.recordDeath(death(UUID_A, 200L), 100);
        assertEquals(2L, data.storedLifetimeDeaths());

        VanillaImportResult imported = data.importVanillaBaselines(Map.of(UUID_A, stats(7, Map.of())), false);
        assertEquals(5, data.player(UUID_A).vanillaDeathsBaseline, "7 vanilla deaths include 2 recorded ones");
        assertEquals(2L, data.storedLifetimeDeaths());
        assertEquals(7L, data.lifetimeDeaths());
        assertEquals(5L, imported.addedDeaths());

        data.recordDeath(death(UUID_A, 300L), 100);
        assertEquals(8L, data.lifetimeDeaths(), "a real death raises the total by exactly one");

        VanillaImportResult reimport = data.importVanillaBaselines(Map.of(UUID_A, stats(8, Map.of())), false);
        assertEquals(0L, reimport.addedDeaths());
        assertEquals(5, data.player(UUID_A).vanillaDeathsBaseline);
        assertEquals(8L, data.lifetimeDeaths());
    }

    @Test
    void causeCountsMergeByMaxAndStayDiagnosticOnly() {
        CensusData data = withPlayer(UUID_A, "Alex");
        data.importVanillaBaselines(Map.of(UUID_A,
                stats(8, Map.of("minecraft:player", 3, "minecraft:spit", 2))), false);
        VanillaImportResult second = data.importVanillaBaselines(Map.of(UUID_A,
                stats(10, Map.of("minecraft:player", 1, "minecraft:spit", 5))), false);
        assertEquals("mob", second.rows().get(0).topCause());
        assertEquals(Map.of("player", 3, "mob", 5), data.player(UUID_A).vanillaBaselineCauses);
        assertEquals(10L, data.lifetimeDeaths());
        assertTrue(data.player(UUID_A).lifetimeCategories.isEmpty(),
                "baseline causes must never feed live cause percentages or category milestones");
    }

    @Test
    void unknownPlayersAndZeroDeathsAreSkippedQuietly() {
        CensusData data = withPlayer(UUID_A, "Alex");
        VanillaImportResult result = data.importVanillaBaselines(Map.of(
                UNKNOWN, stats(99, Map.of()), UUID_A, stats(0, Map.of())), false);
        assertTrue(result.rows().isEmpty());
        assertEquals(1, result.skippedPlayers(), "unknown UUIDs are ignored, zero-death players counted");
        assertEquals(0L, result.addedDeaths());
        assertEquals(1, data.allPlayers().size(), "unknown UUIDs must not become known players");
        assertFalse(data.allPlayers().containsKey(UNKNOWN));
        assertEquals(0, data.player(UUID_A).vanillaDeathsBaseline);
    }

    @Test
    void dryRunReportsTheSameNumbersWithoutWriting() {
        CensusData data = withPlayer(UUID_A, "Alex");
        VanillaImportResult preview = data.importVanillaBaselines(
                Map.of(UUID_A, stats(9, Map.of("minecraft:fall", 2))), true);
        assertEquals(1, preview.rows().size());
        assertEquals(9L, preview.addedDeaths());
        assertEquals("fall", preview.rows().get(0).topCause());
        assertEquals(0, data.player(UUID_A).vanillaDeathsBaseline);
        assertTrue(data.player(UUID_A).vanillaBaselineCauses.isEmpty());
        assertEquals(0L, data.lifetimeDeaths());
    }

    @Test
    void baselinesSurviveSaveLoadSnapshotAndBackupRestore() {
        CensusData data = withPlayer(UUID_A, "Alex");
        CensusData.PlayerCensus pc = data.player(UUID_A);
        pc.vanillaDeathsBaseline = 9;
        pc.vanillaBaselineCauses.put("player", 4);

        CensusData loaded = roundTrip(data);
        assertEquals(9, loaded.player(UUID_A).vanillaDeathsBaseline);
        assertEquals(Map.of("player", 4), loaded.player(UUID_A).vanillaBaselineCauses);
        assertEquals(9L, loaded.vanillaBaselineTotal());
        assertEquals(9L, loaded.lifetimeDeaths());

        List<PlayerDeaths> snapshot = loaded.snapshot();
        assertEquals(9, snapshot.get(0).vanillaDeathsBaseline());
        assertEquals(Map.of("player", 4), snapshot.get(0).vanillaBaselineCauses());
        List<PlayerDeaths> parsed = CensusExport.parseBackupJson(
                ExportRenderers.toBackupJson(snapshot, 1234L));
        assertFalse(parsed.isEmpty());
        assertEquals(9, parsed.get(0).vanillaDeathsBaseline());
        assertEquals(Map.of("player", 4), parsed.get(0).vanillaBaselineCauses());

        CensusData restored = new CensusData();
        restored.replaceAll(parsed);
        assertEquals(9, restored.player(UUID_A).vanillaDeathsBaseline);
        assertEquals(Map.of("player", 4), restored.player(UUID_A).vanillaBaselineCauses);
        assertEquals(9L, restored.lifetimeDeaths());
        assertFalse(ExportRenderers.toJson(snapshot, 1234L).contains("vanillaDeathsBaseline"),
                "public JSON exports must remain unchanged");
    }

    @Test
    void oldSavesAndBackupsDefaultToZeroEmpty() {
        CensusData data = withPlayer(UUID_A, "Alex");
        data.recordDeath(death(UUID_A, 100L), 100);
        CensusData loaded = loadOldSave(data);
        assertEquals(0, loaded.player(UUID_A).vanillaDeathsBaseline);
        assertTrue(loaded.player(UUID_A).vanillaBaselineCauses.isEmpty());
        assertEquals(1L, loaded.lifetimeDeaths());

        List<PlayerDeaths> parsed = CensusExport.parseBackupJson(
                "{\"players\":[{\"uuid\":\"" + UUID_A + "\",\"deaths\":[]}]}");
        assertEquals(0, parsed.get(0).vanillaDeathsBaseline());
        assertTrue(parsed.get(0).vanillaBaselineCauses().isEmpty());
    }

    private static CensusData roundTrip(CensusData data) {
        DynamicOps<Tag> ops = NbtOps.INSTANCE;
        return CensusData.CODEC.parse(ops, CensusData.CODEC.encodeStart(ops, data).getOrThrow()).getOrThrow();
    }

    private static CensusData loadOldSave(CensusData data) {
        DynamicOps<Tag> ops = NbtOps.INSTANCE;
        CompoundTag root = (CompoundTag) CensusData.CODEC.encodeStart(ops, data).getOrThrow();
        CompoundTag player = root.getCompoundOrEmpty("players").getCompoundOrEmpty(UUID_A.toString());
        player.remove("vanillaDeathsBaseline");
        player.remove("vanillaBaselineCauses");
        return CensusData.CODEC.parse(ops, root).getOrThrow();
    }

}
