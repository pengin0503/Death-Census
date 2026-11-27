package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN 1.4.0 §1: bounded nearest selection and the witness ranking. */
class WitnessAnalysisTest {
    private static final long T0 = 1_700_000_000_000L;

    private static DeathEntry death(String uuid, long epoch, List<WitnessRef> witnesses) {
        return new DeathEntry(uuid, uuid, epoch, 1L, 100, 100, "minecraft:overworld",
                1.0, 2.0, 3.0, "fall", "minecraft:fall", uuid + " fell",
                "", "", "survival", 1, "", "", 0, witnesses);
    }

    private static PlayerDeaths player(String uuid, long... epochs) {
        List<WitnessRef> witnesses = List.of(
                new WitnessRef("w1", "Alice"), new WitnessRef("w2", "Bob"),
                new WitnessRef("w3", "Carol"));
        java.util.List<DeathEntry> deaths = new java.util.ArrayList<>();
        for (long epoch : epochs) {
            deaths.add(death(uuid, epoch, witnesses));
        }
        return new PlayerDeaths(uuid, uuid, false, deaths);
    }

    @Test
    void nearestSelectionIsBoundedNearestFirstAndDeterministic() {
        List<WitnessAnalysis.Candidate> candidates = List.of(
                new WitnessAnalysis.Candidate("far", "Far", 400.0),
                new WitnessAnalysis.Candidate("near", "Near", 4.0),
                new WitnessAnalysis.Candidate("middle", "Middle", 100.0),
                new WitnessAnalysis.Candidate("near", "Near duplicate", 1.0),
                new WitnessAnalysis.Candidate("", "No uuid", 0.0),
                new WitnessAnalysis.Candidate("tie-b", "B", 9.0),
                new WitnessAnalysis.Candidate("tie-a", "A", 9.0));

        assertEquals(List.of(new WitnessRef("near", "Near duplicate"),
                        new WitnessRef("tie-a", "A")),
                WitnessAnalysis.nearest(candidates, 2));
        assertEquals(5, WitnessAnalysis.nearest(candidates, 16).size());
        assertEquals(List.of(), WitnessAnalysis.nearest(candidates, 0));
        assertEquals(List.of(), WitnessAnalysis.nearest(List.of(), 8));
        assertEquals(List.of(), WitnessAnalysis.nearest(null, 8));
    }

    @Test
    void nearestUsesUuidTieBreakAndSortsInvalidDistancesLast() {
        List<WitnessAnalysis.Candidate> candidates = List.of(
                new WitnessAnalysis.Candidate("b", "Zed", 10.0),
                new WitnessAnalysis.Candidate("a", "Amy", 10.0),
                new WitnessAnalysis.Candidate("nan", "NaN", Double.NaN),
                new WitnessAnalysis.Candidate("neg", "Negative", -2.0));
        List<WitnessRef> refs = WitnessAnalysis.nearest(candidates, 4);
        assertEquals(List.of("a", "b", "nan", "neg"),
                refs.stream().map(WitnessRef::uuid).toList());
    }

    @Test
    void rankCountsAppearancesPerWitnessAndBreaksTiesDeterministically() {
        List<PlayerDeaths> players = List.of(
                player("victim1", T0, T0 + 10),
                player("victim2", T0 + 20));
        List<WitnessAnalysis.Row> rows = WitnessAnalysis.rank(players);
        // Alice/Bob/Carol each appear three times; ties are by name.
        assertEquals(List.of("Alice", "Bob", "Carol"), rows.stream().map(WitnessAnalysis.Row::name).toList());
        assertEquals(List.of(3, 3, 3), rows.stream().map(WitnessAnalysis.Row::count).toList());
    }

    @Test
    void rankForRestrictsToVictimAndHonoursPeriod() {
        List<PlayerDeaths> players = List.of(
                player("victim", T0 - 10 * 86_400_000L, T0),
                player("other", T0));
        assertEquals(6, WitnessAnalysis.rankFor(players, "victim", 0L).stream()
                .mapToInt(WitnessAnalysis.Row::count).sum());
        assertEquals(3, WitnessAnalysis.rankFor(players, "victim", T0 - 1L).stream()
                .mapToInt(WitnessAnalysis.Row::count).sum());
        assertTrue(WitnessAnalysis.rankFor(players, "missing", 0L).isEmpty());
        assertTrue(WitnessAnalysis.rank(null, 0L).isEmpty());
    }

    @Test
    void rankComposesPrivacyAndResolvesCurrentNames() {
        DeathEntry e = death("victim", T0, List.of(
                new WitnessRef("w1", "Old Alice"), new WitnessRef("w2", "Bob")));
        List<PlayerDeaths> players = List.of(new PlayerDeaths("victim", "Victim", false, List.of(e)));
        Map<String, String> names = Map.of("w1", "New Alice");
        Map<String, PlayerPrivacy> privacy = Map.of(
                "w1", PlayerPrivacy.none(),
                "w2", new PlayerPrivacy(true, false, false, false));

        List<WitnessAnalysis.Row> publicRows = WitnessAnalysis.rank(
                players, null, 0L, names, privacy, false);
        assertEquals(2, publicRows.size());
        WitnessAnalysis.Row named = publicRows.stream()
                .filter(r -> "New Alice".equals(r.name())).findFirst().orElseThrow();
        WitnessAnalysis.Row hidden = publicRows.stream()
                .filter(r -> WitnessRedactor.HIDDEN_NAME_MARKER.equals(r.name())).findFirst().orElseThrow();
        assertEquals(1, named.count()); // captured name replaced by current name
        assertEquals("", hidden.uuid()); // hidden witnesses merge into one anonymous row
        assertEquals(1, hidden.count());

        List<WitnessAnalysis.Row> privilegedRows = WitnessAnalysis.rank(
                players, null, 0L, names, privacy, true);
        assertEquals(2, privilegedRows.size());
        assertTrue(privilegedRows.stream().anyMatch(r -> r.name().equals("Bob")));
        assertTrue(privilegedRows.stream().noneMatch(r -> r.name().equals(WitnessRedactor.HIDDEN_NAME_MARKER)));
    }
}
