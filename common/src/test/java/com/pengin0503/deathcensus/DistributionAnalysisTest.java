package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.4.0 PR-8 (§7): pure time-of-day / Y-band bucket math and privacy composition. */
class DistributionAnalysisTest {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    private static long epoch(int year, int month, int day, int hour, int minute, ZoneId zone) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli();
    }

    private static DeathEntry death(String uuid, long epoch, double y, String cause) {
        return new DeathEntry(uuid, "Player", epoch, 1L, 10, 10, "minecraft:overworld",
                1.0, y, 1.0, cause, "minecraft:" + cause, "died", "", "", "survival", 0, "", "", 0);
    }

    private static PlayerDeaths player(String uuid, boolean optedOut, boolean hideCoordinates,
                                       DeathEntry... deaths) {
        return new PlayerDeaths(uuid, "Player-" + uuid, optedOut, hideCoordinates,
                false, false, List.of(deaths));
    }

    @Test
    void timeBinsSplitAtLocalMidnightInUtc() {
        List<PlayerDeaths> players = List.of(player("p", false, false,
                death("p", epoch(2024, 1, 1, 23, 59, ZoneOffset.UTC), 64.0, "fall"),
                death("p", epoch(2024, 1, 2, 0, 1, ZoneOffset.UTC), 64.0, "fall")));
        List<TimeBin> bins = DistributionAnalysis.timeOfDay(players, 1, ZoneOffset.UTC);
        assertEquals(2, bins.size()); // sorted ascending, so local midnight comes first
        assertEquals(0, bins.get(0).hourStart());
        assertEquals(1, bins.get(0).count());
        assertEquals(23, bins.get(1).hourStart());
        assertEquals(1, bins.get(1).count());
    }

    @Test
    void tokyoZoneRebucketsAcrossTheUtcDayBoundary() {
        long eveningUtc = epoch(2024, 1, 1, 14, 59, ZoneOffset.UTC);   // Tokyo 23:59
        long morningUtc = epoch(2024, 1, 1, 15, 1, ZoneOffset.UTC);    // Tokyo 00:01 (Jan 2)
        List<PlayerDeaths> players = List.of(player("p", false, false,
                death("p", eveningUtc, 64.0, "fall"), death("p", morningUtc, 64.0, "fall")));
        List<TimeBin> utc = DistributionAnalysis.timeOfDay(players, 1, ZoneOffset.UTC);
        assertEquals(List.of(14, 15), utc.stream().map(TimeBin::hourStart).toList());
        List<TimeBin> tokyo = DistributionAnalysis.timeOfDay(players, 1, TOKYO);
        assertEquals(List.of(0, 23), tokyo.stream().map(TimeBin::hourStart).toList());
        assertEquals(1, tokyo.get(0).count());
        assertEquals(1, tokyo.get(1).count());
    }

    @Test
    void allAllowedBinSizesProduceAlignedStartHours() {
        List<DeathEntry> deaths = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            deaths.add(death("p", epoch(2024, 2, 1, hour, 30, ZoneOffset.UTC), 64.0, "fall"));
        }
        List<PlayerDeaths> players = List.of(player("p", false, false, deaths.toArray(DeathEntry[]::new)));
        for (int binHours : List.of(1, 2, 3, 4, 6, 12)) {
            List<TimeBin> bins = DistributionAnalysis.timeOfDay(players, binHours, ZoneOffset.UTC);
            Set<Integer> starts = bins.stream().map(TimeBin::hourStart).collect(Collectors.toSet());
            Set<Integer> expected = deaths.stream()
                    .map(d -> (ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(d.epochMillis()),
                            ZoneOffset.UTC).getHour() / binHours) * binHours)
                    .collect(Collectors.toSet());
            assertEquals(expected, starts, "binHours=" + binHours);
            assertEquals(deaths.size(), bins.stream().mapToInt(TimeBin::count).sum());
        }
    }

    @Test
    void invalidBinSizeFallsBackToTheDefault() {
        List<DeathEntry> deaths = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            deaths.add(death("p", epoch(2024, 2, 1, hour, 0, ZoneOffset.UTC), 64.0, "fall"));
        }
        List<PlayerDeaths> players = List.of(player("p", false, false, deaths.toArray(DeathEntry[]::new)));
        assertEquals(DistributionAnalysis.timeOfDay(players, DistributionAnalysis.DEFAULT_BIN_HOURS,
                        ZoneOffset.UTC),
                DistributionAnalysis.timeOfDay(players, 5, ZoneOffset.UTC));
        assertEquals(DistributionAnalysis.timeOfDay(players, ZoneOffset.UTC),
                DistributionAnalysis.timeOfDay(players, 0, ZoneOffset.UTC));
    }

    @Test
    void yBandsUseFloorDivForNegativeAndHighValues() {
        List<PlayerDeaths> players = List.of(player("p", false, false,
                death("p", 1L, -1.0, "fall"), death("p", 2L, -16.0, "fall"),
                death("p", 3L, -17.0, "fall"), death("p", 4L, 0.0, "fall"),
                death("p", 5L, 15.0, "fall"), death("p", 6L, 16.0, "fall"),
                death("p", 7L, 1_000_007.0, "fall")));
        List<YBand> bands = DistributionAnalysis.yBands(players, 16);
        assertEquals(List.of(-32, -16, 0, 16, 1_000_000),
                bands.stream().map(YBand::minY).toList());
        assertEquals(2, bands.get(1).count()); // -16 and -1
        assertEquals(-1, bands.get(1).maxY());
        assertEquals(2, bands.get(2).count()); // 0 and 15
        assertEquals(15, bands.get(2).maxY());
    }

    @Test
    void yBandBlockSizeIsClampedToTheAllowedRange() {
        DeathEntry y9 = death("p", 1L, 9.0, "fall");
        List<PlayerDeaths> players = List.of(player("p", false, false, y9));
        YBand clampedLow = DistributionAnalysis.yBands(players, 4).get(0);
        assertEquals(8, clampedLow.minY());
        assertEquals(15, clampedLow.maxY());
        DeathEntry y100 = death("p", 2L, 100.0, "fall");
        YBand clampedHigh = DistributionAnalysis.yBands(
                List.of(player("p", false, false, y100)), 100).get(0);
        assertEquals(64, clampedHigh.minY());
        assertEquals(127, clampedHigh.maxY());
    }

    @Test
    void emptyAndNullInputsGiveEmptyTables() {
        assertTrue(DistributionAnalysis.timeOfDay(null, 2, ZoneOffset.UTC).isEmpty());
        assertTrue(DistributionAnalysis.timeOfDay(List.of(), 2, ZoneOffset.UTC).isEmpty());
        assertTrue(DistributionAnalysis.yBands(null, 16).isEmpty());
        assertTrue(DistributionAnalysis.yBands(List.of(), 16).isEmpty());
        List<PlayerDeaths> withNull = java.util.Collections.singletonList(null);
        assertTrue(DistributionAnalysis.timeOfDay(withNull, 2, null).isEmpty());
        assertTrue(DistributionAnalysis.yBands(withNull, 16).isEmpty());
    }

    @Test
    void optedOutDeathsCountAnonymouslyInBothDistributions() {
        long first = epoch(2024, 3, 1, 1, 0, ZoneOffset.UTC);
        long second = epoch(2024, 3, 1, 3, 0, ZoneOffset.UTC);
        List<PlayerDeaths> players = List.of(
                player("secret", true, false, death("secret", first, 64.0, "fall"),
                        death("secret", second, 64.0, "fall")),
                player("public", false, false, death("public", first, -10.0, "mob")));
        long total = CensusStats.totalDeaths(players);
        List<TimeBin> bins = DistributionAnalysis.timeOfDay(players, 2, ZoneOffset.UTC);
        assertEquals(total, bins.stream().mapToInt(TimeBin::count).sum());
        assertEquals(2, bins.size()); // hours 0 and 2, both include the opted-out death
        List<YBand> bands = DistributionAnalysis.yBands(players, 16);
        assertEquals(total, bands.stream().mapToInt(YBand::count).sum());
        for (Object row : new ArrayList<>(bins)) {
            assertFalse(row.toString().contains("secret"), row.toString());
        }
        for (Object row : new ArrayList<>(bands)) {
            assertFalse(row.toString().contains("secret"), row.toString());
        }
    }

    @Test
    void hiddenCoordinatesAreExcludedFromYBandsButNotFromTimeBins() {
        long epoch = epoch(2024, 4, 1, 5, 30, ZoneOffset.UTC);
        DeathEntry flagged = death("hidden", epoch, 100.0, "fall");
        DeathEntry sentinel = death("sentinel", epoch, Double.NaN, "fall");
        List<PlayerDeaths> players = List.of(
                player("hidden", false, true, flagged),
                player("sentinel", false, false, sentinel),
                player("public", false, false, death("public", epoch, 64.0, "fall")));
        List<TimeBin> bins = DistributionAnalysis.timeOfDay(players, 2, ZoneOffset.UTC);
        assertEquals(1, bins.size());
        assertEquals(3, bins.get(0).count());
        List<YBand> bands = DistributionAnalysis.yBands(players, 16);
        assertEquals(1, bands.size());
        assertEquals(64, bands.get(0).minY());
        assertEquals(1, bands.get(0).count());
        assertEquals(3, CensusStats.totalDeaths(players));
    }

    @Test
    void topCauseUsesCountThenAlphabeticalTieBreak() {
        long epoch = epoch(2024, 5, 1, 7, 0, ZoneOffset.UTC);
        List<PlayerDeaths> players = List.of(player("p", false, false,
                death("p", epoch, 64.0, "zombie"), death("p", epoch, 64.0, "alpha")));
        assertEquals("alpha", DistributionAnalysis.timeOfDay(players, 2, ZoneOffset.UTC).get(0).topCause());
        assertEquals("alpha", DistributionAnalysis.yBands(players, 16).get(0).topCause());
    }

    @Test
    void rowsAreSortedAscendingByHourAndBand() {
        long early = epoch(2024, 6, 1, 2, 0, ZoneOffset.UTC);
        long late = epoch(2024, 6, 1, 22, 0, ZoneOffset.UTC);
        List<PlayerDeaths> players = List.of(player("p", false, false,
                death("p", late, 100.0, "fall"), death("p", early, -100.0, "fall")));
        List<Integer> hours = DistributionAnalysis.timeOfDay(players, 2, ZoneOffset.UTC).stream()
                .map(TimeBin::hourStart).toList();
        assertEquals(List.of(2, 22), hours);
        assertTrue(DistributionAnalysis.yBands(players, 16).stream()
                .map(YBand::minY).toList().equals(List.of(-112, 96)));
    }

    @Test
    void inputSnapshotIsNotModified() {
        DeathEntry a = death("p", epoch(2024, 7, 1, 1, 0, ZoneOffset.UTC), 64.0, "fall");
        DeathEntry b = death("p", epoch(2024, 7, 1, 2, 0, ZoneOffset.UTC), -8.0, "mob");
        PlayerDeaths player = player("p", false, false, a, b);
        List<PlayerDeaths> players = List.of(player);
        DistributionAnalysis.timeOfDay(players, 2, ZoneOffset.UTC);
        DistributionAnalysis.yBands(players, 16);
        assertEquals(1, players.size());
        assertTrue(players.get(0) == player);
        assertEquals(2, player.deaths().size());
        assertTrue(player.deaths().get(0) == a);
        assertTrue(player.deaths().get(1) == b);
        assertEquals(64.0, a.y());
    }
}
