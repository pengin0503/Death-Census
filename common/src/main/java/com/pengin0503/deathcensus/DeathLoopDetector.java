package com.pengin0503.deathcensus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure death-loop detection for DESIGN 1.4.0 §8 / §15 Q21–Q23.
 *
 * <p>A loop is a <em>maximal run of consecutive deaths of the same player</em>
 * (a death of that player elsewhere breaks the run; deaths of other players do
 * not). Every death in the run must be in the same dimension, within
 * {@code radiusBlocks} of the <em>first</em> death, and each consecutive time
 * gap must be at most {@code windowMillis}. Runs of at least {@code minDeaths}
 * are returned longest first. The centre is the rounded average x/z and is
 * emitted as whole blocks only. No longest-loop record is persisted: the first
 * row of the ranking is the "longest loop among retained records".
 *
 * <p>Privacy is composed at the boundary: public views exclude opted-out
 * players and players with {@code hideCoordinates}, as well as records already
 * carrying the {@link DeathRedactor#isCoordinateHidden(DeathEntry)} sentinel.
 * Because a loop centre is itself a location disclosure, the exclusion is the
 * same even for admin viewers of a snapshot; {@link #findLoops(List, int, long,
 * int, boolean)} with {@code includePrivate=true} is the deliberately opt-in
 * self/admin view over a full (unredacted) snapshot. Anonymous server totals
 * computed by {@link CensusStats} are never changed by this analyzer.
 */
public final class DeathLoopDetector {
    /** §15 Q21: three deaths, ten minutes, forty-eight blocks, one dimension. */
    public static final int DEFAULT_MIN_DEATHS = 3;
    public static final long DEFAULT_WINDOW_MILLIS = 10L * 60L * 1000L;
    public static final int DEFAULT_RADIUS_BLOCKS = 48;

    private DeathLoopDetector() {
    }

    /** Public view: hidden-coordinate and opted-out deaths never enter a loop. */
    public static List<DeathLoop> findLoops(List<PlayerDeaths> players, int radiusBlocks,
                                            long windowMillis, int minDeaths) {
        return findLoops(players, radiusBlocks, windowMillis, minDeaths, false);
    }

    /** Convenience overload using the §15 defaults. */
    public static List<DeathLoop> findLoops(List<PlayerDeaths> players) {
        return findLoops(players, DEFAULT_RADIUS_BLOCKS, DEFAULT_WINDOW_MILLIS, DEFAULT_MIN_DEATHS);
    }

    /**
     * Snapshot view with an explicit self/admin switch. With
     * {@code includePrivate=false} (the public command, GUI and export), opted-
     * out and hidden-coordinate players are dropped entirely and their deaths
     * still count only in ordinary anonymous totals. With {@code true}, their
     * retained records may form a loop row for the player themself or an admin.
     * The caller is responsible for the viewer gate; nothing here broadcasts.
     */
    public static List<DeathLoop> findLoops(List<PlayerDeaths> players, int radiusBlocks,
                                            long windowMillis, int minDeaths, boolean includePrivate) {
        List<DeathLoopSample> samples = new ArrayList<>();
        if (players != null) {
            for (PlayerDeaths player : players) {
                if (player == null || (!includePrivate
                        && (player.optOutPublic() || player.hideCoordinates()))) {
                    continue;
                }
                for (DeathEntry death : player.deaths()) {
                    if (death == null || (!includePrivate && DeathRedactor.isCoordinateHidden(death))) {
                        continue;
                    }
                    DeathLoopSample sample = DeathLoopSample.of(player.uuid(), player.name(), death);
                    if (sample != null && sample.hasPosition()) {
                        samples.add(sample);
                    }
                }
            }
        }
        return findLoopsFromSamples(samples, radiusBlocks, windowMillis, minDeaths);
    }

    /** Convenience overload using the §15 defaults for a raw tuple list. */
    public static List<DeathLoop> findLoopsFromSamples(List<DeathLoopSample> samples) {
        return findLoopsFromSamples(samples, DEFAULT_RADIUS_BLOCKS,
                DEFAULT_WINDOW_MILLIS, DEFAULT_MIN_DEATHS);
    }

    /**
     * One implementation for both surfaces: the version ring supplies its 16
     * tuples, the snapshot view supplies all retained samples. The input list is
     * never mutated. Named separately from {@link #findLoops(List, int, long, int)}
     * because both list overloads would otherwise erase to the same signature.
     */
    public static List<DeathLoop> findLoopsFromSamples(List<DeathLoopSample> samples, int radiusBlocks,
                                                       long windowMillis, int minDeaths) {
        if (samples == null || samples.isEmpty()) {
            return List.of();
        }
        double radius = Math.max(1.0, radiusBlocks);
        double radiusSquared = radius * radius;
        long window = Math.max(0L, windowMillis);
        int minimum = Math.max(2, minDeaths);

        Map<String, List<DeathLoopSample>> byPlayer = new LinkedHashMap<>();
        for (DeathLoopSample sample : samples) {
            if (sample == null || !sample.hasPosition()) {
                continue;
            }
            byPlayer.computeIfAbsent(sample.uuid(), ignored -> new ArrayList<>()).add(sample);
        }

        List<DeathLoop> loops = new ArrayList<>();
        for (List<DeathLoopSample> owned : byPlayer.values()) {
            // Stable sort: equal epochs keep the retained-record order.
            owned.sort(Comparator.comparingLong(DeathLoopSample::epochMillis));
            List<DeathLoopSample> run = new ArrayList<>();
            for (DeathLoopSample sample : owned) {
                if (!continues(run, sample, radiusSquared, window)) {
                    addIfLoop(loops, run, minimum);
                    run = new ArrayList<>();
                    run.add(sample);
                } else {
                    run.add(sample);
                }
            }
            addIfLoop(loops, run, minimum);
        }

        loops.sort(Comparator.comparingInt(DeathLoop::count).reversed()
                .thenComparingLong(DeathLoop::firstEpoch)
                .thenComparing(DeathLoop::uuid)
                .thenComparing(DeathLoop::dimension)
                .thenComparingInt(DeathLoop::centerX)
                .thenComparingInt(DeathLoop::centerZ));
        return List.copyOf(loops);
    }

    private static boolean continues(List<DeathLoopSample> run, DeathLoopSample sample,
                                     double radiusSquared, long window) {
        if (run.isEmpty()) {
            return true;
        }
        DeathLoopSample first = run.get(0);
        DeathLoopSample previous = run.get(run.size() - 1);
        if (!first.dimension().equals(sample.dimension())
                || sample.epochMillis() - previous.epochMillis() > window) {
            return false;
        }
        double dx = sample.x() - first.x();
        double dz = sample.z() - first.z();
        return dx * dx + dz * dz <= radiusSquared;
    }

    private static void addIfLoop(List<DeathLoop> loops, List<DeathLoopSample> run, int minimum) {
        if (run.size() < minimum) {
            return;
        }
        DeathLoopSample first = run.get(0);
        DeathLoopSample last = run.get(run.size() - 1);
        double sumX = 0.0;
        double sumZ = 0.0;
        Map<String, Integer> causes = new HashMap<>();
        for (DeathLoopSample sample : run) {
            sumX += sample.x();
            sumZ += sample.z();
            causes.merge(sample.causeCategory(), 1, Integer::sum);
        }
        String topCause = causes.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .findFirst().orElse("");
        loops.add(new DeathLoop(first.uuid(), lastName(run), first.dimension(),
                wholeBlock(sumX / run.size()), wholeBlock(sumZ / run.size()), run.size(),
                first.epochMillis(), last.epochMillis(), topCause));
    }

    private static String lastName(List<DeathLoopSample> run) {
        String name = "";
        for (DeathLoopSample sample : run) {
            if (!sample.name().isEmpty()) {
                name = sample.name();
            }
        }
        return name;
    }

    private static int wholeBlock(double value) {
        double rounded = Math.round(value);
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, rounded));
    }
}
