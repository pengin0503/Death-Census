package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN 1.4.0 §5: chunk math, filters, caps and privacy composition. */
class HotspotAnalysisTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    private static DeathEntry death(String dimension, double x, double z, long epoch, String cause) {
        return new DeathEntry("u-" + x + "-" + z, "Victim", epoch, 0L, 0, 0,
                dimension, x, 64.0, z, cause, "minecraft:" + cause, "died", "", "", "survival", 0,
                "", "", 0);
    }

    private static PlayerDeaths player(String uuid, boolean optOut, boolean hideCoordinates,
                                       boolean hidePvpOpponent, DeathEntry... deaths) {
        return new PlayerDeaths(uuid, "Player-" + uuid, optOut, hideCoordinates,
                hidePvpOpponent, false, deaths == null ? List.of() : List.of(deaths));
    }

    private static PlayerDeaths player(DeathEntry... deaths) {
        return player("u-public", false, false, false, deaths);
    }

    private static PlayerDeaths player(List<DeathEntry> deaths) {
        return player(deaths == null ? new DeathEntry[0] : deaths.toArray(new DeathEntry[0]));
    }

    @Test
    void groupsAcrossChunkBoundariesIncludingNegativeCoordinates() {
        List<PlayerDeaths> players = List.of(player(
                death(OVERWORLD, -0.5, -0.5, 100L, "fall"),
                death(OVERWORLD, -1.0, -16.0, 200L, "fall"),
                death(OVERWORLD, 0.0, 0.0, 300L, "fall"),
                death(OVERWORLD, 15.0, 15.0, 400L, "fall"),
                death(OVERWORLD, 16.0, 16.0, 500L, "fall")));
        List<Hotspot> rows = HotspotAnalysis.groupByChunk(players, null, 0L, 16, 1);
        assertEquals(3, rows.size());
        assertEquals(new Hotspot(OVERWORLD, -1, -1, 2, "fall", 200L), rows.get(0));
        assertEquals(0, rows.get(1).chunkX());
        assertEquals(0, rows.get(1).chunkZ());
        assertEquals(1, rows.get(2).chunkX());
        assertEquals(1, rows.get(2).chunkZ());
    }

    @Test
    void chunkSizeIsClampedToEightThroughThirtyTwo() {
        List<DeathEntry> entries = List.of(
                death(OVERWORLD, 7.0, 7.0, 1L, "fall"),
                death(OVERWORLD, 8.0, 8.0, 2L, "fall"));
        List<Hotspot> small = HotspotAnalysis.groupByChunk(List.of(player(entries)), null, 0L, 4, 1);
        assertEquals(2, small.size());
        assertEquals(0, small.get(0).chunkX());
        assertEquals(1, small.get(1).chunkX());

        List<Hotspot> large = HotspotAnalysis.groupByChunk(List.of(player(entries)), null, 0L, 64, 1);
        assertEquals(1, large.size());
        assertEquals(0, large.get(0).chunkX());
    }

    @Test
    void dimensionFilterMatchesExactIdsOnly() {
        List<PlayerDeaths> players = List.of(player(
                death(OVERWORLD, 1.0, 1.0, 1L, "fall"),
                death(OVERWORLD, 2.0, 2.0, 2L, "fall"),
                death("overworld", 3.0, 3.0, 3L, "fall"),
                death(OVERWORLD + "2", 4.0, 4.0, 4L, "fall")));
        List<Hotspot> exact = HotspotAnalysis.groupByChunk(players, OVERWORLD, 0L, 16, 1);
        assertEquals(1, exact.size());
        assertEquals(2, exact.get(0).count());
        assertEquals(3, HotspotAnalysis.groupByChunk(players, null, 0L, 16, 1).size());
        assertEquals(3, HotspotAnalysis.groupByChunk(players, "", 0L, 16, 1).size());
        assertTrue(HotspotAnalysis.groupByChunk(players, "nether", 0L, 16, 1).isEmpty());
    }

    @Test
    void periodCutoffIsInclusiveAndReusesFilterSince() {
        List<PlayerDeaths> players = List.of(player(
                death(OVERWORLD, 1.0, 1.0, 999L, "fall"),
                death(OVERWORLD, 1.0, 1.0, 1000L, "fall")));
        assertEquals(1, HotspotAnalysis.groupByChunk(players, null, 1000L, 16, 1).get(0).count());
        assertEquals(2, HotspotAnalysis.groupByChunk(players, null, 0L, 16, 1).get(0).count());
        assertEquals(CensusStats.totalDeaths(CensusStats.filterSince(players, 1000L)),
                HotspotAnalysis.groupByChunk(players, null, 1000L, 16, 1).get(0).count());
    }

    @Test
    void minimumDeathsThresholdAndDefaultConvenienceOverload() {
        List<PlayerDeaths> players = List.of(player(
                death(OVERWORLD, 1.0, 1.0, 1L, "fall"),
                death(OVERWORLD, 2.0, 2.0, 2L, "fall"),
                death(OVERWORLD, 3.0, 3.0, 3L, "fall")));
        assertEquals(1, HotspotAnalysis.groupByChunk(players, null, 0L, 16, 3).size());
        assertTrue(HotspotAnalysis.groupByChunk(players, null, 0L, 16, 4).isEmpty());
        assertEquals(1, HotspotAnalysis.groupByChunk(players, null, 0L).size());
        assertEquals(1, HotspotAnalysis.groupByChunk(players, null, 0L, 16, 0).size());
    }

    @Test
    void topCauseIsMostFrequentThenAlphabetical() {
        List<PlayerDeaths> players = List.of(player(
                death(OVERWORLD, 1.0, 1.0, 1L, "fall"),
                death(OVERWORLD, 1.0, 1.0, 2L, "creeper"),
                death(OVERWORLD, 1.0, 1.0, 3L, "fall")));
        Hotspot row = HotspotAnalysis.groupByChunk(players, null, 0L, 16, 1).get(0);
        assertEquals("fall", row.topCause());
        assertEquals(3L, row.lastEpoch());

        List<Hotspot> tied = HotspotAnalysis.groupByChunk(List.of(player(
                death(OVERWORLD, 5.0, 5.0, 1L, "zombie"),
                death(OVERWORLD, 5.0, 5.0, 2L, "creeper"))), null, 0L, 16, 1);
        assertEquals("creeper", tied.get(0).topCause());
    }

    @Test
    void rowsAreSortedByCountThenDimensionAndCoordinates() {
        List<PlayerDeaths> players = List.of(player(
                death(NETHER, 1.0, 1.0, 1L, "fall"),
                death(NETHER, 1.0, 1.0, 2L, "fall"),
                death(OVERWORLD, 1.0, 1.0, 3L, "fall"),
                death(OVERWORLD, 1.0, 1.0, 4L, "fall"),
                death(OVERWORLD, 100.0, 1.0, 5L, "fall"),
                death(OVERWORLD, 1.0, 1.0, 6L, "fall")));
        List<Hotspot> rows = HotspotAnalysis.groupByChunk(players, null, 0L, 16, 1);
        assertEquals(3, rows.size());
        assertEquals(3, rows.get(0).count());
        assertEquals(OVERWORLD, rows.get(0).dimension());
        assertEquals(0, rows.get(0).chunkX());
        assertEquals(2, rows.get(1).count());
        assertEquals(NETHER, rows.get(1).dimension());
        assertEquals(1, rows.get(2).count());
    }

    @Test
    void hiddenCoordinateAndOptedOutDeathsAreExcludedButTotalsStay() {
        DeathEntry hiddenFlag = death(OVERWORLD, 8.0, 8.0, 1L, "fall");
        DeathEntry hiddenSentinel = death(OVERWORLD, 9.0, 9.0, 2L, "fall");
        hiddenSentinel = new DeathEntry(hiddenSentinel.playerUuid(), hiddenSentinel.playerName(),
                hiddenSentinel.epochMillis(), hiddenSentinel.gameDay(), hiddenSentinel.playTicks(),
                hiddenSentinel.survivalTicks(), hiddenSentinel.dimension(),
                Double.NaN, Double.NaN, Double.NaN, hiddenSentinel.causeCategory(),
                hiddenSentinel.causeId(), hiddenSentinel.deathMessage(), hiddenSentinel.killerType(),
                hiddenSentinel.killerName(), hiddenSentinel.gameMode(), hiddenSentinel.experienceLevel(),
                hiddenSentinel.excuse(), hiddenSentinel.lastWords(), hiddenSentinel.fCount());
        DeathEntry optedOut = death(OVERWORLD, 10.0, 10.0, 3L, "fall");
        DeathEntry publicDeath = death(OVERWORLD, 11.0, 11.0, 4L, "fall");
        List<PlayerDeaths> players = List.of(
                player("hidden", false, true, false, hiddenFlag),
                player("sentinel", false, false, false, hiddenSentinel),
                player("optout", true, false, false, optedOut),
                player("public", false, false, false, publicDeath));

        List<Hotspot> rows = HotspotAnalysis.groupByChunk(players, null, 0L, 16, 1);
        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).count());
        assertEquals(4, CensusStats.totalDeaths(players));
        assertEquals(4, CensusStats.causeCounts(players, true).stream()
                .filter(e -> "fall".equals(e.getKey())).findFirst().orElseThrow().getValue());
        assertEquals(4, CensusStats.totalDeaths(CensusStats.filterSince(
                DeathRedactor.publicView(players), 0L)));
    }

    @Test
    void hidePvpOpponentDoesNotAffectCells() {
        DeathEntry pvp = death(OVERWORLD, 1.0, 1.0, 1L, "player");
        List<Hotspot> rows = HotspotAnalysis.groupByChunk(
                List.of(player("u", false, false, true, pvp)), null, 0L, 16, 1);
        assertEquals(1, rows.size());
        assertEquals("player", rows.get(0).topCause());
    }

    @Test
    void outputIsCappedDeterministically() {
        List<DeathEntry> entries = new ArrayList<>();
        for (int i = 0; i < HotspotAnalysis.MAX_HOTSPOTS + 20; i++) {
            entries.add(death(OVERWORLD, i * 16.0, 0.0, i, "fall"));
        }
        List<Hotspot> rows = HotspotAnalysis.groupByChunk(List.of(player(entries)), null, 0L, 16, 1);
        assertEquals(HotspotAnalysis.MAX_HOTSPOTS, rows.size());
        for (int i = 1; i < rows.size(); i++) {
            assertTrue(rows.get(i - 1).chunkX() < rows.get(i).chunkX());
        }
    }

    @Test
    void nullAndEmptyInputsAreSafeAndInputIsNotMutated() {
        assertTrue(HotspotAnalysis.groupByChunk(null, null, 0L, 16, 1).isEmpty());
        assertTrue(HotspotAnalysis.groupByChunk(List.of(), null, 0L, 16, 1).isEmpty());
        List<PlayerDeaths> withNulls = new ArrayList<>();
        withNulls.add(null);
        withNulls.add(player((DeathEntry[]) null));
        withNulls.add(player(death(OVERWORLD, 1.0, 1.0, 1L, "fall")));
        int before = CensusStats.totalDeaths(withNulls);
        assertEquals(1, HotspotAnalysis.groupByChunk(withNulls, null, 0L, 16, 1).size());
        assertEquals(before, CensusStats.totalDeaths(withNulls));
        assertFalse(withNulls.isEmpty());
    }
}
