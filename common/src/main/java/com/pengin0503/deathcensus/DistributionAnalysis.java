package com.pengin0503.deathcensus;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure time-of-day and Y-band distributions for DESIGN 1.4.0 §7. Both tables
 * are derived from retained records only; no storage, event or hot-path code is
 * touched. Callers pass snapshots already scoped with {@link CensusStats#filterSince}
 * or {@link CensusStats#filterRange}; this class never mutates its input.
 *
 * <p>Rows are anonymous aggregates and carry no names. Time bins count every
 * death, including opted-out players, as anonymous server totals under DESIGN
 * §8. Y bands are a coarse position hint, so deaths of players with
 * {@code hideCoordinates} (and records already carrying the
 * {@link DeathRedactor#isCoordinateHidden(DeathEntry)} NaN sentinel) are
 * excluded there, exactly as for hotspots. {@code hidePvpOpponent} and
 * {@code discordOptOut} have no effect on either table.
 */
public final class DistributionAnalysis {
    /** §15 Q19: two-hour bins by default; only 1/2/3/4/6/12 are meaningful divisors of a day. */
    public static final int DEFAULT_BIN_HOURS = 2;
    /** §15 Q20: sixteen-block bands by default. */
    public static final int DEFAULT_BAND_BLOCKS = 16;
    public static final int MIN_BAND_BLOCKS = 8;
    public static final int MAX_BAND_BLOCKS = 64;

    private static final List<Integer> ALLOWED_BIN_HOURS = List.of(1, 2, 3, 4, 6, 12);

    private DistributionAnalysis() {
    }

    /**
     * Buckets every death by the local hour in {@code zone}, starting at
     * {@code (hour / binHours) * binHours}. The stored epoch is absolute, so a
     * later {@code displayTimeZone} change re-buckets old records (documented in
     * the command help). Invalid bin sizes fall back to {@link #DEFAULT_BIN_HOURS}.
     */
    public static List<TimeBin> timeOfDay(List<PlayerDeaths> players, int binHours, ZoneId zone) {
        int hours = ALLOWED_BIN_HOURS.contains(binHours) ? binHours : DEFAULT_BIN_HOURS;
        ZoneId z = zone == null ? ZoneId.systemDefault() : zone;
        Map<Integer, List<DeathEntry>> buckets = new TreeMap<>();
        for (PlayerDeaths player : safePlayers(players)) {
            for (DeathEntry death : player.deaths()) {
                if (death == null) {
                    continue;
                }
                ZonedDateTime local = Instant.ofEpochMilli(death.epochMillis()).atZone(z);
                int hourStart = (local.getHour() / hours) * hours;
                buckets.computeIfAbsent(hourStart, ignored -> new ArrayList<>()).add(death);
            }
        }
        List<TimeBin> rows = new ArrayList<>(buckets.size());
        for (Map.Entry<Integer, List<DeathEntry>> entry : buckets.entrySet()) {
            rows.add(new TimeBin(entry.getKey(), entry.getValue().size(), topCause(entry.getValue())));
        }
        return List.copyOf(rows);
    }

    /** Convenience overload using the configured/default two-hour bin. */
    public static List<TimeBin> timeOfDay(List<PlayerDeaths> players, ZoneId zone) {
        return timeOfDay(players, DEFAULT_BIN_HOURS, zone);
    }

    /**
     * Buckets public-location deaths into altitude bands. The lower bound is
     * {@code floorDiv(y, bandBlocks) * bandBlocks} so negative Y values land in
     * the correct band; the upper bound is {@code minY + bandBlocks - 1}.
     * {@code bandBlocks} is clamped to 8–64 before bucketing.
     */
    public static List<YBand> yBands(List<PlayerDeaths> players, int bandBlocks) {
        int size = Math.max(MIN_BAND_BLOCKS, Math.min(MAX_BAND_BLOCKS, bandBlocks));
        Map<Integer, List<DeathEntry>> buckets = new TreeMap<>();
        for (PlayerDeaths player : safePlayers(players)) {
            if (player.hideCoordinates()) {
                continue;
            }
            for (DeathEntry death : player.deaths()) {
                if (!included(death, size)) {
                    continue;
                }
                int minY = Math.floorDiv((int) Math.floor(death.y()), size) * size;
                buckets.computeIfAbsent(minY, ignored -> new ArrayList<>()).add(death);
            }
        }
        List<YBand> rows = new ArrayList<>(buckets.size());
        for (Map.Entry<Integer, List<DeathEntry>> entry : buckets.entrySet()) {
            rows.add(new YBand(entry.getKey(), entry.getKey() + size - 1,
                    entry.getValue().size(), topCause(entry.getValue())));
        }
        return List.copyOf(rows);
    }

    /** Convenience overload using the configured/default sixteen-block band. */
    public static List<YBand> yBands(List<PlayerDeaths> players) {
        return yBands(players, DEFAULT_BAND_BLOCKS);
    }

    private static List<PlayerDeaths> safePlayers(List<PlayerDeaths> players) {
        if (players == null) {
            return List.of();
        }
        List<PlayerDeaths> safe = new ArrayList<>(players.size());
        for (PlayerDeaths player : players) {
            if (player != null) {
                safe.add(player);
            }
        }
        return safe;
    }

    private static boolean included(DeathEntry death, int bandBlocks) {
        if (death == null || DeathRedactor.isCoordinateHidden(death) || !Double.isFinite(death.y())) {
            return false;
        }
        double floor = Math.floor(death.y());
        return floor >= Integer.MIN_VALUE && floor <= Integer.MAX_VALUE
                && bandBlocks > 0;
    }

    /** Same count-desc/cause-asc tie-break as {@link CensusStats#causeCounts}. */
    private static String topCause(List<DeathEntry> deaths) {
        return CensusStats.causeCounts(
                List.of(new PlayerDeaths("", "", false, deaths)), false)
                .stream().findFirst().map(Map.Entry::getKey).orElse("");
    }
}
