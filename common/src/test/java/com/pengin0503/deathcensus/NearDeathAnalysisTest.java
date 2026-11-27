package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN 1.4.0 §10: pure near-death counts, caps, dedupe, privacy and separation. */
class NearDeathAnalysisTest {
    private static DeathEntry death(String uuid, long epoch) {
        return new DeathEntry(uuid, "P", epoch, 0L, 10, 9, "dim",
                1, 2, 3, "fall", "minecraft:fall", "P fell", "", "",
                "survival", 1, "", "", 0);
    }

    private static NearDeathEvent near(String uuid, long epoch, NearDeathEvent.Kind kind, String cause) {
        return new NearDeathEvent(uuid, "P", epoch, "dim", 10, 64, 20, "fall", cause, kind);
    }

    private static PlayerDeaths player(String uuid, String name, boolean optOut, boolean hideCoords,
                                       List<DeathEntry> deaths, List<NearDeathEvent> near) {
        return new PlayerDeaths(uuid, name, optOut, hideCoords, false, false,
                deaths, 0, Map.of(), near);
    }

    @Test
    void countsAndKindSplitStaySeparateFromDeaths() {
        PlayerDeaths alice = player("a", "Alice", false, false, List.of(death("a", 100L)), List.of(
                near("a", 100L, NearDeathEvent.Kind.TOTEM, "minecraft:fall"),
                near("a", 200L, NearDeathEvent.Kind.CANCELLED, "minecraft:mob")));
        PlayerDeaths bob = player("b", "Bob", false, false, List.of(), List.of(
                near("b", 300L, NearDeathEvent.Kind.TOTEM, "minecraft:fall")));
        List<PlayerDeaths> players = List.of(alice, bob);

        NearDeathAnalysis.Split split = NearDeathAnalysis.split(players);
        assertEquals(3, split.total());
        assertEquals(2, split.totem());
        assertEquals(1, split.cancelled());
        assertEquals(3, NearDeathAnalysis.count(players));
        assertEquals(2, NearDeathAnalysis.countFor(alice));
        assertEquals(1, CensusStats.totalDeaths(players));
        assertEquals(1, CensusStats.totalDeaths(List.of(
                player("a", "Alice", false, false, alice.deaths(), List.of()), bob)));
        assertEquals(List.of(), CensusStats.causeCounts(players, true).stream()
                .filter(e -> "minecraft:fall".equals(e.getKey()) && e.getValue() > 1).toList());
    }

    @Test
    void rankingOrdersByCountThenNameWithKindBreakdown() {
        List<PlayerDeaths> players = List.of(
                player("z", "Zed", false, false, List.of(), List.of(
                        near("z", 1L, NearDeathEvent.Kind.TOTEM, "c"),
                        near("z", 2L, NearDeathEvent.Kind.CANCELLED, "c"))),
                player("a", "Alice", false, false, List.of(), List.of(
                        near("a", 3L, NearDeathEvent.Kind.TOTEM, "c"),
                        near("a", 4L, NearDeathEvent.Kind.TOTEM, "c"))),
                player("m", "Mallory", false, false, List.of(), List.of(
                        near("m", 5L, NearDeathEvent.Kind.CANCELLED, "c"))));

        List<NearDeathAnalysis.Row> rows = NearDeathAnalysis.rank(players, 0L);
        assertEquals(List.of("a", "z", "m"), rows.stream().map(NearDeathAnalysis.Row::uuid).toList());
        assertEquals(2, rows.get(0).totem());
        assertEquals(0, rows.get(0).cancelled());
        assertEquals(1, rows.get(1).totem());
        assertEquals(1, rows.get(1).cancelled());
        assertEquals(4L, rows.get(0).lastEpochMillis());
    }

    @Test
    void optOutNamesAreHiddenFromPublicRankingButVisibleToPrivileged() {
        List<PlayerDeaths> players = List.of(player("a", "Alice", true, false, List.of(), List.of(
                near("a", 1L, NearDeathEvent.Kind.TOTEM, "c"))));
        NearDeathAnalysis.Row publicRow = NearDeathAnalysis.rank(players, 0L, Map.of(),
                Map.of("a", new PlayerPrivacy(true, false, false, false)), false).get(0);
        NearDeathAnalysis.Row ownRow = NearDeathAnalysis.rank(players, 0L, Map.of(), Map.of(), true).get(0);

        assertEquals(WitnessRedactor.HIDDEN_NAME_MARKER, publicRow.name());
        assertEquals(1, publicRow.total()); // anonymous count survives
        assertEquals("Alice", ownRow.name());
    }

    @Test
    void hiddenCoordinatesUseDeathRedactorSentinel() {
        NearDeathEvent event = near("a", 1L, NearDeathEvent.Kind.TOTEM, "c");
        PlayerPrivacy privacy = new PlayerPrivacy(false, true, false, false);
        NearDeathEvent view = DeathRedactor.apply(event, privacy, false);

        assertTrue(DeathRedactor.isNearDeathCoordinateHidden(view));
        assertEquals(10.0, event.x()); // original untouched
        assertSame(event, DeathRedactor.apply(event, privacy, true));
        assertEquals("~, ~, ~", NearDeathAnalysis.positionLabel(view));
        assertEquals("10, 64, 20", NearDeathAnalysis.positionLabel(event));
    }

    @Test
    void hiddenIdentitiesScrubNearDeathNames() {
        NearDeathEvent event = new NearDeathEvent("a", "Alice", 1L, "dim", 1, 2, 3,
                "fall", "Alice hit", NearDeathEvent.Kind.CANCELLED);
        PlayerDeaths secret = player("a", "Alice", true, false, List.of(), List.of(event));
        HiddenIdentities hidden = HiddenIdentities.of(List.of(secret));
        NearDeathEvent view = hidden.scrub(event);

        assertEquals(WitnessRedactor.HIDDEN_NAME_MARKER, view.name());
        assertFalse(view.causeId().contains("Alice"));
        assertEquals(event.epochMillis(), view.epochMillis());
    }

    @Test
    void dedupeKeysCollapseBothHooksWithinWindow() {
        long window = 2000L;
        NearDeathEvent totem = near("a", 10_100L, NearDeathEvent.Kind.TOTEM, "minecraft:fall");
        NearDeathEvent cancelled = near("a", 10_900L, NearDeathEvent.Kind.CANCELLED, "minecraft:fall");
        NearDeathEvent later = near("a", 13_000L, NearDeathEvent.Kind.TOTEM, "minecraft:fall");
        NearDeathEvent otherCause = near("a", 10_500L, NearDeathEvent.Kind.TOTEM, "minecraft:mob");

        assertEquals(NearDeathAnalysis.dedupeKey(totem, window),
                NearDeathAnalysis.dedupeKey(cancelled, window));
        assertFalse(NearDeathAnalysis.dedupeKey(totem, window).equals(
                NearDeathAnalysis.dedupeKey(later, window)));
        assertFalse(NearDeathAnalysis.dedupeKey(totem, window).equals(
                NearDeathAnalysis.dedupeKey(otherCause, window)));
        assertTrue(NearDeathAnalysis.isDuplicate(List.of(totem), cancelled, window));
        assertFalse(NearDeathAnalysis.isDuplicate(List.of(totem), later, window));
        assertEquals("a|5|minecraft:fall", NearDeathAnalysis.dedupeKey(totem, window));
        assertEquals(500L, NearDeathAnalysis.dedupWindow(1L));
        assertEquals(10_000L, NearDeathAnalysis.dedupWindow(99_999L));
    }

    @Test
    void dedupeKeySeparatesPlayers() {
        assertTrue(NearDeathAnalysis.dedupeKey("a", 100L, "c", 2000L)
                .equals(NearDeathAnalysis.dedupeKey("A", 100L, "C", 2000L)) == false);
        assertEquals(NearDeathAnalysis.dedupeKey("a", 100L, "c", 2000L),
                NearDeathAnalysis.dedupeKey("a", 100L, " c ", 2000L));
    }

    @Test
    void appendBoundedDropsOldestAndIgnoresSentinels() {
        List<NearDeathEvent> existing = new ArrayList<>(List.of(
                near("a", 1L, NearDeathEvent.Kind.TOTEM, "c"),
                near("a", 2L, NearDeathEvent.Kind.CANCELLED, "c")));
        NearDeathAnalysis.CapResult result = NearDeathAnalysis.appendBounded(existing,
                java.util.Arrays.asList(near("a", 3L, NearDeathEvent.Kind.TOTEM, "c"),
                        NearDeathEvent.EMPTY, null), 2);

        assertEquals(1, result.dropped());
        assertEquals(List.of(2L, 3L), result.events().stream()
                .map(NearDeathEvent::epochMillis).toList());
        assertEquals(2, existing.size()); // input untouched
        assertTrue(NearDeathAnalysis.appendBounded(null, null, 0).events().isEmpty());
    }

    @Test
    void historyIsNewestFirstAndPeriodScoped() {
        java.util.UUID uuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000061");
        String id = uuid.toString();
        List<PlayerDeaths> players = List.of(player(id, "Alice", false, false, List.of(), List.of(
                near(id, 100L, NearDeathEvent.Kind.TOTEM, "c"),
                near(id, 300L, NearDeathEvent.Kind.CANCELLED, "c"),
                near(id, 200L, NearDeathEvent.Kind.TOTEM, "c"))));

        List<NearDeathEvent> all = NearDeathAnalysis.history(players, uuid, 0L);
        assertEquals(List.of(300L, 200L, 100L), all.stream().map(NearDeathEvent::epochMillis).toList());
        assertEquals(List.of(300L), NearDeathAnalysis.history(players, uuid, 250L).stream()
                .map(NearDeathEvent::epochMillis).toList());
        assertTrue(NearDeathAnalysis.history(players, null, 0L).isEmpty());
    }

    @Test
    void nullAndEmptyInputsAreSafe() {
        assertEquals(0, NearDeathAnalysis.count(null));
        assertEquals(NearDeathAnalysis.Split.EMPTY, NearDeathAnalysis.split(null));
        assertTrue(NearDeathAnalysis.rank(null, 0L).isEmpty());
        assertTrue(NearDeathAnalysis.history(null, java.util.UUID.randomUUID(), 0L).isEmpty());
        assertTrue(NearDeathAnalysis.exportLines(null, null).isEmpty());
        assertEquals(0, NearDeathAnalysis.countFor(null));
        assertFalse(NearDeathAnalysis.isDuplicate(null, NearDeathEvent.EMPTY, 2000L));
        assertEquals("|0|", NearDeathAnalysis.dedupeKey(null, 0L, null, 0L));
    }

    @Test
    void periodFiltersPreserveNearDeathsWithoutChangingDeathTotals() {
        PlayerDeaths pd = player("a", "Alice", false, false,
                List.of(death("a", 100L)), List.of(near("a", 200L, NearDeathEvent.Kind.TOTEM, "c")));
        List<PlayerDeaths> since = CensusStats.filterSince(List.of(pd), 150L);

        assertEquals(0, CensusStats.totalDeaths(since));
        assertEquals(1, NearDeathAnalysis.count(since));
        assertEquals(1, NearDeathAnalysis.count(CensusStats.filterRange(List.of(pd), 150L, 250L)));
    }

    @Test
    void anonymizedCopiesKeepNearDeathsSeparateAndPseudonymized() {
        String id = "00000000-0000-0000-0000-000000000061";
        PlayerDeaths pd = player(id, "Alice", false, false,
                List.of(death(id, 100L)), List.of(near(id, 200L, NearDeathEvent.Kind.TOTEM, "c")));
        PlayerDeaths anon = Anonymizer.apply(List.of(pd)).get(0);

        assertEquals(1, anon.nearDeaths().size());
        assertEquals("Player A", anon.nearDeaths().get(0).name());
        assertEquals(1, CensusStats.totalDeaths(List.of(anon)));
    }

    @Test
    void exportLinesArePrivacyProjectedAndSeparate() {
        PlayerDeaths alice = player("a", "Alice", false, false, List.of(),
                List.of(near("a", 1L, NearDeathEvent.Kind.TOTEM, "c"),
                        near("a", 2L, NearDeathEvent.Kind.CANCELLED, "c")));
        PlayerDeaths secret = player("s", "Secret", true, false, List.of(), List.of(
                near("s", 3L, NearDeathEvent.Kind.TOTEM, "c")));
        List<String> lines = NearDeathAnalysis.exportLines(List.of(alice, secret), List.of(alice, secret));

        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("Alice"));
        assertTrue(lines.get(0).contains("totem 1"));
        assertTrue(lines.get(1).contains(WitnessRedactor.HIDDEN_NAME_MARKER));
        String json = ExportRenderers.toJson(List.of(alice), 100L);
        assertTrue(json.contains("\"nearDeaths\""));
        assertTrue(json.contains("\"total\": 2"));
        String csv = ExportRenderers.toCsv(List.of(alice));
        assertTrue(csv.contains("near_death_section,near_death_line"));
    }
}
