package com.pengin0503.deathcensus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure derived awards/titles for DESIGN 1.4.0 §2.
 *
 * <p>Every rule is evaluated over a period-scoped, public-view
 * {@code List<PlayerDeaths>}; the caller supplies that snapshot through
 * {@link CensusStats#filterSince}. Winners are deterministic: value descending,
 * then winner UUID lexicographically. No results are persisted and no Minecraft
 * or version API is used here.
 */
public final class Awards {
    /** The §2 award/title set, in stable presentation order. */
    public enum Rule {
        MOST_DEATHS("most_deaths"),
        LONGEST_SURVIVAL("longest_survival"),
        MOST_FALLS("most_falls"),
        MOST_LAVA("most_lava"),
        MOST_LIGHTNING("most_lightning"),
        MOST_RESPECTED("most_respected"),
        CATEGORY_COMPLETE("category_complete"),
        RAREST_CAUSE("rarest_cause"),
        WITNESS_FAMOUS("witness_famous");

        private final String token;

        Rule(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }

        public static Rule fromToken(String value) {
            if (value == null) {
                return null;
            }
            for (Rule rule : values()) {
                if (rule.token.equalsIgnoreCase(value) || rule.name().equalsIgnoreCase(value)) {
                    return rule;
                }
            }
            return null;
        }
    }

    /**
     * Version-supplied facts that cannot be derived from retained deaths alone:
     * per-player lifetime category completion and the anonymous rarest cause of
     * the full (pre-privacy-filter) snapshot.
     */
    public record Context(Map<String, Set<String>> lifetimeCategories, String rarestCause) {
        public static final Context EMPTY = new Context(Map.of(), "");

        public Context {
            Map<String, Set<String>> copy = new LinkedHashMap<>();
            if (lifetimeCategories != null) {
                for (Map.Entry<String, Set<String>> entry : lifetimeCategories.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) {
                        continue;
                    }
                    copy.put(entry.getKey(), Set.copyOf(entry.getValue().stream()
                            .filter(value -> value != null && !value.isBlank()).toList()));
                }
            }
            lifetimeCategories = Map.copyOf(copy);
            rarestCause = rarestCause == null ? "" : rarestCause;
        }

        public static Context of(Map<String, Set<String>> lifetimeCategories) {
            return new Context(lifetimeCategories, "");
        }

        public Set<String> categories(String uuid) {
            if (uuid == null) {
                return Set.of();
            }
            Set<String> values = lifetimeCategories.get(uuid);
            Set<String> lower = new LinkedHashSet<>();
            if (values != null) {
                for (String value : values) {
                    lower.add(value.toLowerCase(java.util.Locale.ROOT));
                }
            }
            return Set.copyOf(lower);
        }
    }

    private static final int MIN_COUNT_FLOOR = 1;
    private static final int MIN_COUNT_CEILING = 100;

    private Awards() {
    }

    public static List<AwardResult> evaluate(Collection<PlayerDeaths> players) {
        return evaluate(players, MIN_COUNT_FLOOR, Context.EMPTY);
    }

    public static List<AwardResult> evaluate(Collection<PlayerDeaths> players, int minCount) {
        return evaluate(players, minCount, Context.EMPTY);
    }

    public static List<AwardResult> evaluate(Collection<PlayerDeaths> players, int minCount, Context context) {
        int threshold = Math.max(MIN_COUNT_FLOOR, Math.min(MIN_COUNT_CEILING, minCount));
        Context effective = context == null ? Context.EMPTY : context;
        List<PlayerDeaths> publicPlayers = publicPlayers(players);
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, List<DeathEntry>> deathsByPlayer = new LinkedHashMap<>();
        for (PlayerDeaths player : publicPlayers) {
            names.put(player.uuid(), player.name());
            deathsByPlayer.put(player.uuid(), player.deaths());
        }
        if (deathsByPlayer.isEmpty()) {
            return List.of();
        }

        List<DeathEntry> allDeaths = new ArrayList<>();
        for (List<DeathEntry> deaths : deathsByPlayer.values()) {
            allDeaths.addAll(deaths);
        }

        List<AwardResult> results = new ArrayList<>(9);
        add(results, Rule.MOST_DEATHS, maxByCount(countDeaths(deathsByPlayer), names), threshold, "");
        DeathEntry longest = allDeaths.stream()
                .max(Comparator.<DeathEntry>comparingInt(DeathEntry::survivalTicks)
                        .thenComparing(DeathEntry::playerUuid, Comparator.reverseOrder()))
                .orElse(null);
        if (longest != null) {
            add(results, Rule.LONGEST_SURVIVAL,
                    new Winner(longest.playerUuid(), longest.playerName(), longest.survivalTicks()),
                    threshold, "");
        }
        add(results, Rule.MOST_FALLS, maxByCount(countCause(deathsByPlayer, "fall"), names), threshold, "fall");
        add(results, Rule.MOST_LAVA, maxByCount(countCause(deathsByPlayer, "lava"), names), threshold, "lava");
        add(results, Rule.MOST_LIGHTNING,
                maxByCount(countCause(deathsByPlayer, "lightning"), names), threshold, "lightning");
        Map<String, Integer> respects = new HashMap<>();
        for (Map.Entry<String, List<DeathEntry>> entry : deathsByPlayer.entrySet()) {
            int total = 0;
            for (DeathEntry death : entry.getValue()) {
                total += death.fCount();
            }
            respects.put(entry.getKey(), total);
        }
        add(results, Rule.MOST_RESPECTED, maxByCount(respects, names), threshold, "");

        Map<String, Integer> categories = new HashMap<>();
        boolean anyCategoryData = false;
        for (String uuid : deathsByPlayer.keySet()) {
            Set<String> held = effective.categories(uuid);
            if (!held.isEmpty()) {
                anyCategoryData = true;
            }
            int complete = 0;
            for (String category : CauseClassifier.CATEGORIES) {
                if (held.contains(category.toLowerCase(java.util.Locale.ROOT))) {
                    complete++;
                }
            }
            categories.put(uuid, complete);
        }
        if (anyCategoryData) {
            Winner complete = maxByCount(categories, names);
            if (complete != null && complete.count() == CauseClassifier.CATEGORIES.size()) {
                add(results, Rule.CATEGORY_COMPLETE, complete, threshold,
                        Integer.toString(CauseClassifier.CATEGORIES.size()));
            }
        }

        String rarest = effective.rarestCause().isBlank()
                ? CensusStats.rarestCause(publicPlayers, false).orElse("")
                : effective.rarestCause();
        if (!rarest.isEmpty()) {
            Map<String, Integer> holders = countCause(deathsByPlayer, rarest);
            Winner winner = maxByCount(holders, names);
            if (winner != null) {
                add(results, Rule.RAREST_CAUSE, winner, threshold, rarest);
            }
        }

        // PR-2 dependency: the witness title exists only when at least one
        // retained record actually carries a witness list.
        List<WitnessAnalysis.Row> witnessRows = WitnessAnalysis.rank(
                publicPlayers, null, 0L,
                WitnessRedactor.namesById(players), WitnessRedactor.privacyById(players), false).stream()
                .filter(row -> !WitnessRedactor.HIDDEN_NAME_MARKER.equals(row.name()))
                .toList();
        if (!witnessRows.isEmpty()) {
            WitnessAnalysis.Row top = witnessRows.stream()
                    .max(Comparator.<WitnessAnalysis.Row>comparingInt(WitnessAnalysis.Row::count)
                            .thenComparing(WitnessAnalysis.Row::uuid, Comparator.reverseOrder()))
                    .orElseThrow();
            add(results, Rule.WITNESS_FAMOUS,
                    new Winner(top.uuid(), top.name(), top.count()), threshold, "");
        }
        return List.copyOf(results);
    }

    private record Winner(String uuid, String name, int count) {
    }

    private static void add(List<AwardResult> results, Rule rule, Winner winner,
                            int threshold, String detail) {
        if (winner == null || winner.count() < threshold) {
            return;
        }
        results.add(new AwardResult(rule, winner.uuid(), winner.name(), winner.count(), detail));
    }

    /** Count-descending, then UUID lexicographically ascending for determinism. */
    private static Winner maxByCount(Map<String, Integer> counts, Map<String, String> names) {
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .max(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(entry -> new Winner(entry.getKey(),
                        names.getOrDefault(entry.getKey(), entry.getKey()), entry.getValue()))
                .orElse(null);
    }

    private static Map<String, Integer> countDeaths(Map<String, List<DeathEntry>> deathsByPlayer) {
        Map<String, Integer> counts = new HashMap<>();
        for (Map.Entry<String, List<DeathEntry>> entry : deathsByPlayer.entrySet()) {
            counts.put(entry.getKey(), entry.getValue().size());
        }
        return counts;
    }

    private static Map<String, Integer> countCause(Map<String, List<DeathEntry>> deathsByPlayer, String cause) {
        Map<String, Integer> counts = new HashMap<>();
        for (Map.Entry<String, List<DeathEntry>> entry : deathsByPlayer.entrySet()) {
            int total = 0;
            for (DeathEntry death : entry.getValue()) {
                if (cause.equalsIgnoreCase(death.causeCategory())) {
                    total++;
                }
            }
            counts.put(entry.getKey(), total);
        }
        return counts;
    }

    private static List<PlayerDeaths> publicPlayers(Collection<PlayerDeaths> players) {
        if (players == null) {
            return List.of();
        }
        List<PlayerDeaths> result = new ArrayList<>();
        for (PlayerDeaths player : players) {
            if (player != null && !player.optOutPublic() && !player.deaths().isEmpty()) {
                result.add(player);
            }
        }
        return result;
    }
}
