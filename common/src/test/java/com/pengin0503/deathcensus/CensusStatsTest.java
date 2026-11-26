package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CensusStatsTest {

    private static DeathEntry death(
            String uuid,
            String name,
            long epochMillis,
            int survivalTicks,
            String category
    ) {
        return new DeathEntry(
                uuid,
                name,
                epochMillis,
                1L,
                survivalTicks + 100,
                survivalTicks,
                "minecraft:overworld",
                0.0,
                64.0,
                0.0,
                category,
                "minecraft:" + category,
                name + " died",
                "",
                "",
                "survival",
                5,
                "",
                "",
                0
        );
    }

    @Test
    void causeCountsOrderingAndOptOutFiltering() {
        PlayerDeaths alice = new PlayerDeaths("u1", "Alice", false, List.of(
                death("u1", "Alice", 1000L, 100, "lava"),
                death("u1", "Alice", 2000L, 200, "fall"),
                death("u1", "Alice", 3000L, 300, "fall")
        ));
        PlayerDeaths bob = new PlayerDeaths("u2", "Bob", true, List.of(
                death("u2", "Bob", 4000L, 400, "lava"),
                death("u2", "Bob", 5000L, 500, "drown")
        ));
        List<PlayerDeaths> players = List.of(alice, bob);

        assertEquals(5, CensusStats.totalDeaths(players));

        List<Map.Entry<String, Integer>> publicCounts = CensusStats.causeCounts(players, false);
        assertEquals(List.of(Map.entry("fall", 2), Map.entry("lava", 1)), publicCounts);

        List<Map.Entry<String, Integer>> allCounts = CensusStats.causeCounts(players, true);
        // fall=2, lava=2 (fall before lava alphabetically), drown=1
        assertEquals(
                List.of(Map.entry("fall", 2), Map.entry("lava", 2), Map.entry("drown", 1)),
                allCounts
        );
    }

    @Test
    void summariesComputeShortestLongestEpochsAndTopCategory() {
        PlayerDeaths bob = new PlayerDeaths("u2", "bob", false, List.of(
                death("u2", "bob", 5000L, 0, "void"),
                death("u2", "bob", 1000L, 0, "fall")
        ));
        PlayerDeaths alice = new PlayerDeaths("u1", "Alice", false, List.of(
                death("u1", "Alice", 3000L, 0, "lava"),
                death("u1", "Alice", 2000L, 500, "fall"),
                death("u1", "Alice", 4000L, 1200, "fall")
        ));
        PlayerDeaths zero = new PlayerDeaths("u3", "Charlie", false, List.of());
        PlayerDeaths hidden = new PlayerDeaths("u4", "Dana", true, List.of(
                death("u4", "Dana", 6000L, 900, "fire")
        ));

        List<CensusStats.PlayerSummary> summaries =
                CensusStats.summaries(List.of(bob, alice, zero, hidden), false);

        assertEquals(2, summaries.size());
        CensusStats.PlayerSummary first = summaries.get(0);
        assertEquals("Alice", first.name());
        assertEquals(3, first.deaths());
        assertEquals(1200, first.longestSurvivalTicks());
        assertEquals(500, first.shortestSurvivalTicks());
        assertEquals(2000L, first.firstEpochMillis());
        assertEquals(4000L, first.lastEpochMillis());
        assertEquals("fall", first.topCategory());

        CensusStats.PlayerSummary second = summaries.get(1);
        assertEquals("bob", second.name());
        assertEquals(2, second.deaths());
        assertEquals(0, second.longestSurvivalTicks());
        assertEquals(-1, second.shortestSurvivalTicks());
        assertEquals(1000L, second.firstEpochMillis());
        assertEquals(5000L, second.lastEpochMillis());
        // tie 1-1 between fall and void -> fall alphabetically
        assertEquals("fall", second.topCategory());
    }

    @Test
    void leaderboardsAndRarestCauseTieBreak() {
        DeathEntry d1 = death("u1", "Zoe", 3000L, 1000, "mob");
        DeathEntry d2 = death("u1", "Zoe", 1000L, 500, "drown");
        DeathEntry d3 = death("u2", "Amy", 2000L, 1000, "cactus");
        DeathEntry d4 = death("u2", "Amy", 4000L, 0, "mob");
        DeathEntry d5 = death("u3", "Hidden", 500L, 50, "void");

        List<PlayerDeaths> players = List.of(
                new PlayerDeaths("u1", "Zoe", false, List.of(d1, d2)),
                new PlayerDeaths("u2", "Amy", false, List.of(d3, d4)),
                new PlayerDeaths("u3", "Hidden", true, List.of(d5))
        );

        // Leaderboard deaths: tie 2-2 -> Amy before Zoe
        List<CensusStats.PlayerSummary> byDeaths = CensusStats.leaderboardDeaths(players, false);
        assertEquals(List.of("Amy", "Zoe"), byDeaths.stream().map(CensusStats.PlayerSummary::name).toList());

        // Leaderboard survival: 1000 (d3 epoch 2000 before d1 epoch 3000), 500 (d2), 0 (d4)
        List<DeathEntry> bySurvival = CensusStats.leaderboardSurvival(players, false);
        assertEquals(List.of(d3, d1, d2, d4), bySurvival);

        // Leaderboard fastest: excludes 0 survival; d2 (500) < d3 (1000, epoch 2000) < d1 (1000, epoch 3000)
        List<DeathEntry> fastest = CensusStats.leaderboardFastest(players, false);
        assertEquals(List.of(d2, d3, d1), fastest);

        // Rarest cause among public: cactus=1, drown=1, mob=2 -> tie between cactus and drown -> "cactus"
        assertEquals(Optional.of("cactus"), CensusStats.rarestCause(players, false));
        assertTrue(CensusStats.rarestCause(List.of(), false).isEmpty());

        // deathsWithCause sorted by epochMillis asc
        assertEquals(List.of(d1, d4), CensusStats.deathsWithCause(players, "mob", false));
    }

    @Test
    void modelsNormalizeNullsAndDefensiveCopy() {
        DeathEntry entry = new DeathEntry(
                null, null, 0L, 0L, -5, -10,
                null, 0, 0, 0, null, null, null, null, null, null, -3,
                null, null, -7
        );
        assertEquals("", entry.playerUuid());
        assertEquals("", entry.playerName());
        assertEquals(0, entry.playTicks());
        assertEquals(0, entry.survivalTicks());
        assertEquals(0, entry.experienceLevel());
        assertEquals("", entry.deathMessage());
        assertEquals("", entry.excuse());
        assertEquals("", entry.lastWords());
        assertEquals(0, entry.fCount());

        List<DeathEntry> mutable = new ArrayList<>();
        mutable.add(entry);
        PlayerDeaths pd = new PlayerDeaths(null, null, false, mutable);
        mutable.clear();
        assertEquals("", pd.uuid());
        assertEquals("", pd.name());
        assertEquals(1, pd.deaths().size());
        assertTrue(new PlayerDeaths("u", "n", false, null).deaths().isEmpty());
    }

    @Test
    void filterSinceKeepsBoundaryEntriesAndPlayerMetadata() {
        DeathEntry before = death("u1", "Alice", 999L, 10, "fall");
        DeathEntry boundary = death("u1", "Alice", 1000L, 20, "fall");
        DeathEntry after = death("u1", "Alice", 1001L, 30, "lava");
        PlayerDeaths alice = new PlayerDeaths("u1", "Alice", true, List.of(before, boundary, after));
        PlayerDeaths bob = new PlayerDeaths("u2", "Bob", false, List.of(
                death("u2", "Bob", 500L, 40, "drown")));

        List<PlayerDeaths> filtered = CensusStats.filterSince(List.of(alice, bob), 1000L);

        assertEquals(2, filtered.size());
        PlayerDeaths filteredAlice = filtered.get(0);
        assertEquals("u1", filteredAlice.uuid());
        assertEquals("Alice", filteredAlice.name());
        assertTrue(filteredAlice.optOutPublic());
        // Boundary entry (epoch == since) is included; older entry is dropped.
        assertEquals(List.of(boundary, after), filteredAlice.deaths());

        PlayerDeaths filteredBob = filtered.get(1);
        assertEquals("u2", filteredBob.uuid());
        assertEquals("Bob", filteredBob.name());
        assertFalse(filteredBob.optOutPublic());
        // A player with no remaining deaths stays present with an empty list.
        assertTrue(filteredBob.deaths().isEmpty());
        assertEquals(2, CensusStats.totalDeaths(filtered));

        // The input snapshot is not mutated.
        assertEquals(3, alice.deaths().size());
        assertEquals(1, bob.deaths().size());

        // Empty input and fully filtered-out input.
        assertTrue(CensusStats.filterSince(List.of(), 1000L).isEmpty());
        List<PlayerDeaths> empty = CensusStats.filterSince(List.of(alice), 10_000L);
        assertEquals(1, empty.size());
        assertTrue(empty.get(0).deaths().isEmpty());
    }

    @Test
    void filterSinceNonPositiveReturnsEquivalentUnfilteredCopies() {
        PlayerDeaths alice = new PlayerDeaths("u1", "Alice", true, List.of(
                death("u1", "Alice", 500L, 10, "fall")));

        List<PlayerDeaths> copies = CensusStats.filterSince(List.of(alice), 0L);

        assertEquals(1, copies.size());
        assertNotSame(alice, copies.get(0));
        assertNotSame(alice.deaths(), copies.get(0).deaths());
        assertEquals(alice.deaths(), copies.get(0).deaths());
        assertEquals("u1", copies.get(0).uuid());
        assertEquals("Alice", copies.get(0).name());
        assertTrue(copies.get(0).optOutPublic());
    }

    @Test
    void filterRangeKeepsBothInclusiveBoundsAndMetadata() {
        DeathEntry before = death("u1", "Alice", -1000L, 10, "fall");
        DeathEntry from = death("u1", "Alice", 0L, 20, "lava");
        DeathEntry inside = death("u1", "Alice", 500L, 30, "lava");
        DeathEntry to = death("u1", "Alice", 1000L, 40, "fall");
        DeathEntry after = death("u1", "Alice", 1001L, 50, "fall");
        PlayerDeaths alice = new PlayerDeaths("u1", "Alice", true,
                List.of(before, from, inside, to, after));
        PlayerDeaths bob = new PlayerDeaths("u2", "Bob", false, List.of(
                death("u2", "Bob", 500L, 60, "drown")));

        List<PlayerDeaths> ranged = CensusStats.filterRange(List.of(alice, bob), 0L, 1000L);

        assertEquals(2, ranged.size());
        assertEquals("u1", ranged.get(0).uuid());
        assertEquals("Alice", ranged.get(0).name());
        assertTrue(ranged.get(0).optOutPublic());
        // Both bounds are inclusive; epoch:0 is a real lower bound, not "no cutoff".
        assertEquals(List.of(from, inside, to), ranged.get(0).deaths());
        assertEquals(List.of(bob.deaths().get(0)), ranged.get(1).deaths());
        assertEquals(4, CensusStats.totalDeaths(ranged));

        assertTrue(CensusStats.filterRange(null, 0L, 1L).isEmpty());
        assertTrue(CensusStats.filterRange(List.of(alice), 10L, 9L).isEmpty());
        assertEquals(5, alice.deaths().size());
    }
}
