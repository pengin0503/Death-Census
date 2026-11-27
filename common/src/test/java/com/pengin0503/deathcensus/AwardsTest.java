package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN 1.4.0 §2: deterministic derived awards over a public snapshot. */
class AwardsTest {
    private static DeathEntry death(String uuid, String name, long epoch, int survival,
                                    String cause, int fCount, WitnessRef... witnesses) {
        return new DeathEntry(uuid, name, epoch, 1L, survival, survival,
                "minecraft:overworld", 0, 64, 0, cause, "minecraft:" + cause,
                name + " died", "", "", "survival", 0, "", "", fCount, List.of(witnesses));
    }

    private static PlayerDeaths player(String uuid, String name, boolean optOut, DeathEntry... deaths) {
        return new PlayerDeaths(uuid, name, optOut, List.of(deaths));
    }

    private static AwardResult find(List<AwardResult> results, Awards.Rule rule) {
        return results.stream().filter(result -> result.rule() == rule).findFirst().orElse(null);
    }

    @Test
    void emptyAndSinglePlayerSnapshots() {
        assertTrue(Awards.evaluate(null).isEmpty());
        assertTrue(Awards.evaluate(List.of()).isEmpty());
        List<AwardResult> single = Awards.evaluate(List.of(player(
                "u-alice", "Alice", false, death("u-alice", "Alice", 100L, 40, "fall", 0))));
        assertEquals("Alice", find(single, Awards.Rule.MOST_DEATHS).name());
        assertEquals(1, find(single, Awards.Rule.MOST_DEATHS).count());
        assertEquals(40, find(single, Awards.Rule.LONGEST_SURVIVAL).count());
        assertEquals("fall", find(single, Awards.Rule.MOST_FALLS).detail());
    }

    @Test
    void countsUseValueDescendingThenUuidLexicographicTieBreak() {
        List<PlayerDeaths> players = List.of(
                player("u-zoe", "Zoe", false,
                        death("u-zoe", "Zoe", 1L, 1, "lava", 0),
                        death("u-zoe", "Zoe", 2L, 1, "lava", 0),
                        death("u-zoe", "Zoe", 3L, 1, "lava", 0)),
                player("u-alice", "Alice", false,
                        death("u-alice", "Alice", 4L, 1, "lava", 0),
                        death("u-alice", "Alice", 5L, 1, "lava", 0),
                        death("u-alice", "Alice", 6L, 1, "lava", 0)));
        List<AwardResult> results = Awards.evaluate(players);
        assertEquals("Alice", find(results, Awards.Rule.MOST_DEATHS).name());
        assertEquals("Alice", find(results, Awards.Rule.MOST_LAVA).name());
    }

    @Test
    void longestSurvivalUsesTieBreakAndRespectUsesFCount() {
        List<PlayerDeaths> players = List.of(
                player("u-bob", "Bob", false, death("u-bob", "Bob", 1L, 800, "fall", 2)),
                player("u-alice", "Alice", false,
                        death("u-alice", "Alice", 2L, 800, "fall", 5),
                        death("u-alice", "Alice", 3L, 100, "lava", 5)));
        List<AwardResult> results = Awards.evaluate(players);
        assertEquals("Alice", find(results, Awards.Rule.LONGEST_SURVIVAL).name());
        assertEquals("Alice", find(results, Awards.Rule.MOST_RESPECTED).name());
        assertEquals(10, find(results, Awards.Rule.MOST_RESPECTED).count());
    }

    @Test
    void causeRulesAreIndependent() {
        List<PlayerDeaths> players = List.of(
                player("u-a", "A", false,
                        death("u-a", "A", 1L, 1, "fall", 0),
                        death("u-a", "A", 2L, 1, "lightning", 0)),
                player("u-b", "B", false,
                        death("u-b", "B", 3L, 1, "lava", 0),
                        death("u-b", "B", 4L, 1, "lava", 0),
                        death("u-b", "B", 5L, 1, "lava", 0)));
        List<AwardResult> results = Awards.evaluate(players);
        assertEquals("A", find(results, Awards.Rule.MOST_FALLS).name());
        assertEquals("A", find(results, Awards.Rule.MOST_LIGHTNING).name());
        assertEquals("B", find(results, Awards.Rule.MOST_LAVA).name());
        assertEquals("A", find(results, Awards.Rule.RAREST_CAUSE).name());
        // fall and lightning tie at one death; the lexicographically smaller wins.
        assertEquals("fall", find(results, Awards.Rule.RAREST_CAUSE).detail());
    }

    @Test
    void categoryCompleteUsesVersionSuppliedLifetimeCategories() {
        List<PlayerDeaths> players = List.of(player("u-a", "A", false,
                death("u-a", "A", 1L, 1, "fall", 0)));
        Map<String, Set<String>> partial = Map.of("u-a", Set.of("fall", "lava"));
        assertTrue(find(Awards.evaluate(players, 1, Awards.Context.of(partial)),
                Awards.Rule.CATEGORY_COMPLETE) == null);
        Map<String, Set<String>> complete = Map.of("u-a", Set.copyOf(CauseClassifier.CATEGORIES));
        AwardResult result = find(Awards.evaluate(players, 1, Awards.Context.of(complete)),
                Awards.Rule.CATEGORY_COMPLETE);
        assertEquals("A", result.name());
        assertEquals(CauseClassifier.CATEGORIES.size(), result.count());
    }

    @Test
    void rarestCauseUsesAnonymousFullSnapshotButNamesOnlyPublicHolders() {
        List<PlayerDeaths> players = List.of(
                player("u-secret", "Secret", true,
                        death("u-secret", "Secret", 1L, 1, "magic", 0)),
                player("u-a", "A", false,
                        death("u-a", "A", 2L, 1, "fall", 0),
                        death("u-a", "A", 3L, 1, "fall", 0)));
        Awards.Context context = new Awards.Context(Map.of(), "magic");
        List<AwardResult> results = Awards.evaluate(players, 1, context);
        assertFalse(results.stream().anyMatch(result -> "Secret".equals(result.name())));
        assertFalse(find(results, Awards.Rule.RAREST_CAUSE) != null);
        assertEquals("A", find(results, Awards.Rule.MOST_DEATHS).name());
    }

    @Test
    void witnessFamousOnlyEvaluatesWhenWitnessRecordsExist() {
        List<PlayerDeaths> empty = List.of(player("u-a", "A", false,
                death("u-a", "A", 1L, 1, "fall", 0)));
        assertTrue(find(Awards.evaluate(empty), Awards.Rule.WITNESS_FAMOUS) == null);

        PlayerDeaths witnessed = player("u-a", "A", false,
                death("u-a", "A", 1L, 1, "fall", 0,
                        new WitnessRef("u-b", "B"), new WitnessRef("u-b", "B")));
        AwardResult result = find(Awards.evaluate(List.of(witnessed), 1, Awards.Context.EMPTY),
                Awards.Rule.WITNESS_FAMOUS);
        assertEquals("B", result.name());
        assertEquals(2, result.count());
    }

    @Test
    void optedOutWitnessNeverBecomesNamedWinner() {
        WitnessRef hidden = new WitnessRef("u-hidden", "Hidden");
        PlayerDeaths victim = player("u-a", "A", false,
                death("u-a", "A", 1L, 1, "fall", 0, hidden, hidden));
        PlayerDeaths optedOut = player("u-hidden", "Hidden", true,
                death("u-hidden", "Hidden", 2L, 1, "fall", 0));
        List<AwardResult> results = Awards.evaluate(List.of(victim, optedOut));
        // Anonymous witness counts still exist, but no title is attached to them.
        assertTrue(find(results, Awards.Rule.WITNESS_FAMOUS) == null);
        assertFalse(find(results, Awards.Rule.MOST_DEATHS).name().equals("Hidden"));
    }

    @Test
    void minCountSuppressesLowWinnersAndClampsToConfiguredRange() {
        List<PlayerDeaths> players = List.of(
                player("u-a", "A", false, death("u-a", "A", 1L, 1, "fall", 0)),
                player("u-b", "B", false,
                        death("u-b", "B", 2L, 1, "fall", 0),
                        death("u-b", "B", 3L, 1, "fall", 0)));
        List<AwardResult> results = Awards.evaluate(players, 2);
        assertEquals("B", find(results, Awards.Rule.MOST_DEATHS).name());
        assertFalse(results.stream().anyMatch(result -> "A".equals(result.name())));
        assertTrue(Awards.evaluate(players, 1000).isEmpty());
    }

    @Test
    void periodBoundariesAreAppliedByTheCaller() {
        long from = 1_000L;
        DeathEntry before = death("u-a", "A", from - 1L, 1, "fall", 0);
        DeathEntry atBoundary = death("u-a", "A", from, 1, "fall", 0);
        List<PlayerDeaths> inPeriod = CensusStats.filterSince(
                List.of(player("u-a", "A", false, before, atBoundary)), from);
        AwardResult result = find(Awards.evaluate(inPeriod), Awards.Rule.MOST_DEATHS);
        assertEquals(1, result.count());
        assertEquals(atBoundary.epochMillis(), inPeriod.get(0).deaths().get(0).epochMillis());
    }

    @Test
    void evaluateDoesNotMutateItsInput() {
        DeathEntry entry = death("u-a", "A", 1L, 1, "fall", 0);
        List<PlayerDeaths> players = List.of(player("u-a", "A", false, entry));
        Map<String, Set<String>> categories = new LinkedHashMap<>();
        categories.put("u-a", Set.of("fall"));
        Awards.evaluate(players, 1, new Awards.Context(categories, "fall"));
        assertEquals(1, players.get(0).deaths().size());
        assertEquals(1, categories.get("u-a").size());
    }
}
