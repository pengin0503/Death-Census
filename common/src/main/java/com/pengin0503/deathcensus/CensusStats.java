package com.pengin0503.deathcensus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CensusStats {
    public record PlayerSummary(
            String uuid,
            String name,
            int deaths,
            int longestSurvivalTicks,
            int shortestSurvivalTicks,
            long firstEpochMillis,
            long lastEpochMillis,
            String topCategory
    ) {
        public PlayerSummary {
            uuid = uuid == null ? "" : uuid;
            name = name == null ? "" : name;
            topCategory = topCategory == null ? "" : topCategory;
        }
    }

    private CensusStats() {
    }

    /**
     * Returns new player snapshots whose death lists contain only entries at or
     * after {@code sinceMillis}. A non-positive cutoff keeps every death. Players
     * with no remaining deaths stay in the result with an empty death list.
     */
    public static List<PlayerDeaths> filterSince(List<PlayerDeaths> players, long sinceMillis) {
        if (players == null) {
            return List.of();
        }
        List<PlayerDeaths> filtered = new ArrayList<>(players.size());
        for (PlayerDeaths player : players) {
            if (player == null) {
                continue;
            }
            List<DeathEntry> deaths = sinceMillis <= 0L
                    ? player.deaths()
                    : player.deaths().stream()
                            .filter(death -> death != null && death.epochMillis() >= sinceMillis)
                            .toList();
            filtered.add(new PlayerDeaths(
                    player.uuid(),
                    player.name(),
                    player.optOutPublic(),
                    player.hideCoordinates(),
                    player.hidePvpOpponent(),
                    player.discordOptOut(),
                    deaths,
                    player.vanillaDeathsBaseline(),
                    player.vanillaBaselineCauses(),
                    player.nearDeaths()
            ));
        }
        return List.copyOf(filtered);
    }

    /**
     * Returns new snapshots containing only deaths in the inclusive window
     * {@code [fromMillisInclusive, toMillisInclusive]} (DESIGN 1.3.0 §5). This
     * is the upper-bounded companion to {@link #filterSince}; null players are
     * skipped, players with no in-range deaths remain with an empty list, and
     * uuid/name/opt-out metadata is preserved so aggregate callers can still
     * count opted-out deaths anonymously.
     */
    public static List<PlayerDeaths> filterRange(
            List<PlayerDeaths> players, long fromMillisInclusive, long toMillisInclusive) {
        if (players == null || toMillisInclusive < fromMillisInclusive) {
            return List.of();
        }
        List<PlayerDeaths> ranged = new ArrayList<>(players.size());
        for (PlayerDeaths player : players) {
            if (player == null) {
                continue;
            }
            // Do not delegate to filterSince: its non-positive cutoff means
            // "no lower bound", while epoch:0 and negative epochs are valid here.
            List<DeathEntry> deaths = player.deaths().stream()
                    .filter(death -> death != null
                            && death.epochMillis() >= fromMillisInclusive
                            && death.epochMillis() <= toMillisInclusive)
                    .toList();
            ranged.add(new PlayerDeaths(
                    player.uuid(),
                    player.name(),
                    player.optOutPublic(),
                    player.hideCoordinates(),
                    player.hidePvpOpponent(),
                    player.discordOptOut(),
                    deaths,
                    player.vanillaDeathsBaseline(),
                    player.vanillaBaselineCauses(),
                    player.nearDeaths()));
        }
        return List.copyOf(ranged);
    }

    public static int totalDeaths(Collection<PlayerDeaths> players) {
        if (players == null) {
            return 0;
        }
        int total = 0;
        for (PlayerDeaths player : players) {
            if (player != null) {
                total += player.deaths().size();
            }
        }
        return total;
    }

    public static List<Map.Entry<String, Integer>> causeCounts(
            Collection<PlayerDeaths> players,
            boolean includeOptOut
    ) {
        if (players == null) {
            return List.of();
        }
        Map<String, Integer> counts = new HashMap<>();
        for (PlayerDeaths player : players) {
            if (player == null || (!includeOptOut && player.optOutPublic())) {
                continue;
            }
            for (DeathEntry death : player.deaths()) {
                counts.merge(death.causeCategory(), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(e -> Map.entry(e.getKey(), e.getValue()))
                .toList();
    }

    public static List<PlayerSummary> summaries(
            Collection<PlayerDeaths> players,
            boolean includeOptOut
    ) {
        if (players == null) {
            return List.of();
        }
        List<PlayerSummary> result = new ArrayList<>();
        for (PlayerDeaths player : players) {
            if (player == null || (!includeOptOut && player.optOutPublic())) {
                continue;
            }
            List<DeathEntry> deaths = player.deaths();
            if (deaths.isEmpty()) {
                continue;
            }
            int longest = 0;
            int shortest = Integer.MAX_VALUE;
            boolean hasPositiveSurvival = false;
            long firstEpoch = Long.MAX_VALUE;
            long lastEpoch = Long.MIN_VALUE;
            Map<String, Integer> catCounts = new HashMap<>();

            for (DeathEntry death : deaths) {
                int survival = death.survivalTicks();
                if (survival > longest) {
                    longest = survival;
                }
                if (survival > 0 && survival < shortest) {
                    shortest = survival;
                    hasPositiveSurvival = true;
                }
                long epoch = death.epochMillis();
                if (epoch < firstEpoch) {
                    firstEpoch = epoch;
                }
                if (epoch > lastEpoch) {
                    lastEpoch = epoch;
                }
                catCounts.merge(death.causeCategory(), 1, Integer::sum);
            }

            String topCategory = catCounts.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                            .reversed()
                            .thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse("");

            result.add(new PlayerSummary(
                    player.uuid(),
                    player.name(),
                    deaths.size(),
                    longest,
                    hasPositiveSurvival ? shortest : -1,
                    firstEpoch,
                    lastEpoch,
                    topCategory
            ));
        }

        result.sort(Comparator.comparing(PlayerSummary::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PlayerSummary::uuid));
        return List.copyOf(result);
    }

    public static List<PlayerSummary> leaderboardDeaths(
            Collection<PlayerDeaths> players,
            boolean includeOptOut
    ) {
        return summaries(players, includeOptOut).stream()
                .sorted(Comparator.comparingInt(PlayerSummary::deaths)
                        .reversed()
                        .thenComparing(PlayerSummary::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(PlayerSummary::uuid))
                .toList();
    }

    public static List<DeathEntry> leaderboardSurvival(
            Collection<PlayerDeaths> players,
            boolean includeOptOut
    ) {
        return collectDeaths(players, includeOptOut).stream()
                .sorted(Comparator.comparingInt(DeathEntry::survivalTicks)
                        .reversed()
                        .thenComparingLong(DeathEntry::epochMillis))
                .toList();
    }

    public static List<DeathEntry> leaderboardFastest(
            Collection<PlayerDeaths> players,
            boolean includeOptOut
    ) {
        return collectDeaths(players, includeOptOut).stream()
                .filter(d -> d.survivalTicks() > 0)
                .sorted(Comparator.comparingInt(DeathEntry::survivalTicks)
                        .thenComparingLong(DeathEntry::epochMillis))
                .toList();
    }

    public static Optional<String> rarestCause(
            Collection<PlayerDeaths> players,
            boolean includeOptOut
    ) {
        return causeCounts(players, includeOptOut).stream()
                .min(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey);
    }

    public static List<DeathEntry> deathsWithCause(
            Collection<PlayerDeaths> players,
            String category,
            boolean includeOptOut
    ) {
        if (players == null || category == null) {
            return List.of();
        }
        return collectDeaths(players, includeOptOut).stream()
                .filter(d -> category.equals(d.causeCategory()))
                .sorted(Comparator.comparingLong(DeathEntry::epochMillis))
                .toList();
    }

    private static List<DeathEntry> collectDeaths(
            Collection<PlayerDeaths> players,
            boolean includeOptOut
    ) {
        if (players == null) {
            return List.of();
        }
        List<DeathEntry> all = new ArrayList<>();
        for (PlayerDeaths player : players) {
            if (player == null || (!includeOptOut && player.optOutPublic())) {
                continue;
            }
            all.addAll(player.deaths());
        }
        return all;
    }
}
