package com.pengin0503.deathcensus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure hotspot grouping for DESIGN 1.4.0 §5. Deaths are bucketed into square
 * chunks of {@code chunkSize} blocks using {@link Math#floorDiv(int, int)} on
 * x/z, so negative coordinates fall into the correct cell. Everything is
 * derived from retained records; no storage, event or hot-path code is touched.
 *
 * <p>Privacy is composed here once for every surface: a cell is itself a
 * location disclosure, so deaths of fully opted-out players and of players with
 * {@code hideCoordinates} (including records already carrying the
 * {@link DeathRedactor#isCoordinateHidden(DeathEntry)} NaN sentinel) are
 * excluded entirely. {@code hidePvpOpponent} is irrelevant because no names or
 * messages are attached to cells. Anonymous server totals are computed from the
 * unredacted snapshot by {@link CensusStats} and stay untouched. The returned
 * rows therefore describe "public-location deaths" only.
 *
 * <p>Output is bounded by {@link #MAX_HOTSPOTS}: after the minimum-count filter,
 * the best cells by count/dimension/chunk are kept, giving every surface a
 * deterministic top-N view.
 */
public final class HotspotAnalysis {
    public static final int DEFAULT_CHUNK_SIZE = 16;
    public static final int MIN_CHUNK_SIZE = 8;
    public static final int MAX_CHUNK_SIZE = 32;
    public static final int DEFAULT_MIN_DEATHS = 3;
    /** SPEC §5 leaves the top-N number open; this bounds every projection. */
    public static final int MAX_HOTSPOTS = 100;

    private HotspotAnalysis() {
    }

    /**
     * Groups public-location deaths into chunks and returns the top cells.
     *
     * @param players         unredacted snapshot (never mutated)
     * @param dimensionFilter exact dimension id, or null/blank for all dimensions
     * @param sinceMillis     inclusive lower epoch bound; non-positive means no bound
     * @param chunkSize       clamped to 8–32 blocks (default 16)
     * @param minDeaths       minimum deaths per cell, at least 1
     */
    public static List<Hotspot> groupByChunk(List<PlayerDeaths> players, String dimensionFilter,
                                             long sinceMillis, int chunkSize, int minDeaths) {
        int size = Math.max(MIN_CHUNK_SIZE, Math.min(MAX_CHUNK_SIZE, chunkSize));
        int threshold = Math.max(1, minDeaths);
        String filter = dimensionFilter == null || dimensionFilter.isBlank() ? "" : dimensionFilter;
        List<PlayerDeaths> inPeriod = CensusStats.filterSince(players, sinceMillis);
        if (inPeriod.isEmpty()) {
            return List.of();
        }

        Map<String, Cell> cells = new LinkedHashMap<>();
        for (PlayerDeaths player : inPeriod) {
            if (player == null || player.optOutPublic() || player.hideCoordinates()) {
                continue;
            }
            for (DeathEntry death : player.deaths()) {
                if (death == null || !included(death, filter, size)) {
                    continue;
                }
                int chunkX = Math.floorDiv((int) Math.floor(death.x()), size);
                int chunkZ = Math.floorDiv((int) Math.floor(death.z()), size);
                String key = death.dimension() + '\u0000' + chunkX + '\u0000' + chunkZ;
                Cell cell = cells.computeIfAbsent(key,
                        ignored -> new Cell(death.dimension(), chunkX, chunkZ));
                cell.deaths.add(death);
                cell.lastEpoch = Math.max(cell.lastEpoch, death.epochMillis());
            }
        }

        List<Hotspot> rows = new ArrayList<>();
        for (Cell cell : cells.values()) {
            if (cell.deaths.size() < threshold) {
                continue;
            }
            String topCause = CensusStats.causeCounts(
                    List.of(new PlayerDeaths("", "", false, cell.deaths)), false)
                    .stream().findFirst().map(Map.Entry::getKey).orElse("");
            rows.add(new Hotspot(cell.dimension, cell.chunkX, cell.chunkZ,
                    cell.deaths.size(), topCause, cell.lastEpoch));
        }
        rows.sort(Comparator.comparingInt(Hotspot::count).reversed()
                .thenComparing(Hotspot::dimension)
                .thenComparingInt(Hotspot::chunkX)
                .thenComparingInt(Hotspot::chunkZ)
                .thenComparing(Hotspot::topCause));
        return List.copyOf(rows.size() > MAX_HOTSPOTS ? rows.subList(0, MAX_HOTSPOTS) : rows);
    }

    /** Convenience overload using the configured/default chunk size and minimum. */
    public static List<Hotspot> groupByChunk(List<PlayerDeaths> players, String dimensionFilter,
                                             long sinceMillis) {
        return groupByChunk(players, dimensionFilter, sinceMillis,
                DEFAULT_CHUNK_SIZE, DEFAULT_MIN_DEATHS);
    }

    private static boolean included(DeathEntry death, String dimensionFilter, int chunkSize) {
        if (DeathRedactor.isCoordinateHidden(death)
                || !Double.isFinite(death.x()) || !Double.isFinite(death.z())) {
            return false;
        }
        if (!dimensionFilter.isEmpty() && !dimensionFilter.equals(death.dimension())) {
            return false;
        }
        // Guard against coordinates whose floor value cannot be narrowed to int.
        double x = Math.floor(death.x());
        double z = Math.floor(death.z());
        return x >= Integer.MIN_VALUE && x <= Integer.MAX_VALUE
                && z >= Integer.MIN_VALUE && z <= Integer.MAX_VALUE
                && chunkSize > 0;
    }

    private static final class Cell {
        private final String dimension;
        private final int chunkX;
        private final int chunkZ;
        private final List<DeathEntry> deaths = new ArrayList<>();
        private long lastEpoch;

        private Cell(String dimension, int chunkX, int chunkZ) {
            this.dimension = dimension;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }
}
