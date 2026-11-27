package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure chronicle rules: triggers, ordering, retention, reconstruction and privacy (§3). */
class ChronicleRulesTest {

    private static DeathEntry death(long epoch, long day, String dimension, String killerType) {
        return death(epoch, day, "u1", "Alice", dimension, killerType, "fall");
    }

    private static DeathEntry death(long epoch, long day, String uuid, String name,
                                    String dimension, String killerType) {
        return death(epoch, day, uuid, name, dimension, killerType, "fall");
    }

    private static DeathEntry death(long epoch, long day, String uuid, String name,
                                    String dimension, String killerType, String category) {
        return new DeathEntry(uuid, name, epoch, day, 100, 100, dimension, 1.0, 64.0, 2.0,
                category, "minecraft:" + category, "killed", killerType, "",
                "survival", 5, "", "", 0, List.of(), DeathContext.EMPTY);
    }

    private static List<ChronicleEvent.Kind> kinds(List<ChronicleEvent> events) {
        return events.stream().map(ChronicleEvent::kind).toList();
    }

    @Test
    void multipleTriggersProduceRowsInStableOrder() {
        DeathEntry entry = death(1_000L, 3L, "minecraft:the_nether",
                ChronicleRules.END_DRAGON_KILLER_TYPE);
        List<ChronicleEvent> events = ChronicleRules.eventsFor(entry,
                new ChronicleRules.Counters(99, false, 16, 17));
        assertEquals(List.of(
                ChronicleEvent.Kind.NETHER_FIRST,
                ChronicleEvent.Kind.END_DRAGON_DEATH,
                ChronicleEvent.Kind.TOTAL_COUNT,
                ChronicleEvent.Kind.CATEGORY_COMPLETE), kinds(events));
        assertEquals(100, events.get(2).count());
        assertEquals("", events.get(2).name());
        assertEquals("", events.get(2).uuid());
        assertEquals("", events.get(2).dimension());
        assertEquals("", events.get(2).causeCategory());
        assertEquals("u1", events.get(3).uuid());
        assertEquals(17, events.get(3).count());
        assertTrue(events.stream().noneMatch(ChronicleEvent::reconstructed));
    }

    @Test
    void serverFirstFiresOnTheFirstDeath() {
        DeathEntry entry = death(1_000L, 1L, "minecraft:overworld", "");
        assertEquals(List.of(ChronicleEvent.Kind.SERVER_FIRST),
                kinds(ChronicleRules.eventsFor(entry, new ChronicleRules.Counters(0, true, 0, 1))));
    }

    @Test
    void totalCountFiresOnlyOnEveryHundredthDeath() {
        DeathEntry entry = death(1_000L, 2L, "minecraft:overworld", "");
        assertEquals(List.of(), kinds(ChronicleRules.eventsFor(entry,
                new ChronicleRules.Counters(0, true, 0, 1))).stream()
                .filter(k -> k == ChronicleEvent.Kind.TOTAL_COUNT).toList());
        assertTrue(ChronicleRules.eventsFor(entry, new ChronicleRules.Counters(99, true, 0, 1))
                .stream().anyMatch(e -> e.kind() == ChronicleEvent.Kind.TOTAL_COUNT && e.count() == 100));
        assertTrue(ChronicleRules.eventsFor(entry, new ChronicleRules.Counters(199, true, 0, 1))
                .stream().anyMatch(e -> e.kind() == ChronicleEvent.Kind.TOTAL_COUNT && e.count() == 200));
        assertEquals(List.of(), ChronicleRules.eventsFor(entry,
                new ChronicleRules.Counters(100, true, 0, 1)).stream()
                .filter(e -> e.kind() == ChronicleEvent.Kind.TOTAL_COUNT).toList());
    }

    @Test
    void firstsDoNotRepeatAndDragonUsesOnlyStoredKillerType() {
        DeathEntry nether = death(2_000L, 3L, "minecraft:the_nether", "");
        assertEquals(List.of(), kinds(ChronicleRules.eventsFor(nether,
                new ChronicleRules.Counters(50, true, 4, 5))));
        DeathEntry namedDragon = death(3_000L, 4L, "minecraft:overworld", "");
        assertEquals(List.of(), ChronicleRules.eventsFor(namedDragon,
                new ChronicleRules.Counters(50, true, 4, 5)));
        DeathEntry dragon = death(3_000L, 4L, "minecraft:overworld",
                ChronicleRules.END_DRAGON_KILLER_TYPE);
        assertEquals(List.of(ChronicleEvent.Kind.END_DRAGON_DEATH),
                kinds(ChronicleRules.eventsFor(dragon, new ChronicleRules.Counters(50, true, 4, 5))));
    }

    @Test
    void noDataProducesNoEvents() {
        assertEquals(List.of(), ChronicleRules.eventsFor(null, new ChronicleRules.Counters(0, false, 0, 0)));
        assertEquals(List.of(ChronicleEvent.Kind.SERVER_FIRST),
                kinds(ChronicleRules.eventsFor(death(1L, 1L, "minecraft:overworld", ""), null)));
    }

    @Test
    void categoryCompletionRequiresTheFullSeventeen() {
        DeathEntry entry = death(1_000L, 5L, "minecraft:overworld", "");
        assertEquals(List.of(), ChronicleRules.eventsFor(entry,
                new ChronicleRules.Counters(50, true, 16, 16)));
        assertEquals(List.of(ChronicleEvent.Kind.CATEGORY_COMPLETE),
                kinds(ChronicleRules.eventsFor(entry,
                        new ChronicleRules.Counters(50, true, 16, CauseClassifier.CATEGORIES.size()))));
    }

    @Test
    void reconstructionDerivesOnlyProvableRowsAndFlagsAllOfThem() {
        DeathEntry first = death(1_000L, 1L, "minecraft:overworld", "");
        DeathEntry nether = death(2_000L, 2L, "u1", "Alice", "minecraft:the_nether", "", "lava");
        DeathEntry dragon = death(3_000L, 3L, "u2", "Bob", "minecraft:the_end",
                ChronicleRules.END_DRAGON_KILLER_TYPE, "mob");
        PlayerDeaths player = new PlayerDeaths("u1", "Alice", false, List.of(first, nether));
        PlayerDeaths other = new PlayerDeaths("u2", "Bob", false, List.of(dragon));
        Set<String> all = new HashSet<>(CauseClassifier.CATEGORIES);
        ChronicleRules.ReconstructionResult result = ChronicleRules.reconstruct(
                new ChronicleRules.ReconstructionInput(List.of(player, other), 250, Map.of("u1", all)));
        // M4: undated counter/category rows sort after the provably dated ones.
        assertEquals(List.of(
                ChronicleEvent.Kind.SERVER_FIRST,
                ChronicleEvent.Kind.NETHER_FIRST,
                ChronicleEvent.Kind.END_DRAGON_DEATH,
                ChronicleEvent.Kind.TOTAL_COUNT,
                ChronicleEvent.Kind.TOTAL_COUNT,
                ChronicleEvent.Kind.CATEGORY_COMPLETE), kinds(result.events()));
        assertTrue(result.events().stream().allMatch(ChronicleEvent::reconstructed));
        assertEquals("u2", result.events().get(2).uuid());
        for (ChronicleEvent row : result.events()) {
            if (row.kind() == ChronicleEvent.Kind.TOTAL_COUNT
                    || row.kind() == ChronicleEvent.Kind.CATEGORY_COMPLETE) {
                assertTrue(ChronicleRules.isUndated(row));
                assertEquals(0L, row.epochMillis());
            }
        }
        assertEquals(0, result.dropped());
    }

    @Test
    void reconstructionNeverInventsAbsentCountersOrCategories() {
        PlayerDeaths player = new PlayerDeaths("u1", "Alice", false,
                List.of(death(1_000L, 1L, "minecraft:overworld", "")));
        ChronicleRules.ReconstructionResult empty = ChronicleRules.reconstruct(
                new ChronicleRules.ReconstructionInput(List.of(player), null, null));
        assertEquals(List.of(ChronicleEvent.Kind.SERVER_FIRST), kinds(empty.events()));
        ChronicleRules.ReconstructionResult noTotal = ChronicleRules.reconstruct(
                new ChronicleRules.ReconstructionInput(List.of(player), 99, null));
        assertEquals(List.of(ChronicleEvent.Kind.SERVER_FIRST), kinds(noTotal.events()));
        assertEquals(List.of(), ChronicleRules.reconstruct(
                new ChronicleRules.ReconstructionInput(List.of(), null, null)).events());
    }

    @Test
    void retentionDropsOldestRowsFirst() {
        List<ChronicleEvent> stored = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            stored.add(new ChronicleEvent(i, 1L, ChronicleEvent.Kind.TOTAL_COUNT,
                    "", "", "", "", i * 100, false));
        }
        ChronicleRules.CapResult capped = ChronicleRules.cap(stored, 3);
        assertEquals(2, capped.dropped());
        assertEquals(List.of(3L, 4L, 5L),
                capped.events().stream().map(ChronicleEvent::epochMillis).toList());
        ChronicleRules.CapResult appended = ChronicleRules.appendBounded(stored,
                List.of(new ChronicleEvent(6L, 1L, ChronicleEvent.Kind.NETHER_FIRST,
                        "u1", "A", "minecraft:the_nether", "fall", 1, false)), 3);
        assertEquals(3, appended.dropped());
        assertEquals(List.of(4L, 5L, 6L),
                appended.events().stream().map(ChronicleEvent::epochMillis).toList());
        assertEquals(0, ChronicleRules.cap(stored, 200).dropped());
    }

    @Test
    void reconstructedHistoryIsCappedAndReportsDroppedRows() {
        List<DeathEntry> deaths = new ArrayList<>();
        for (int i = 1; i <= 210; i++) {
            deaths.add(death(i, 1L, "minecraft:overworld", ""));
        }
        ChronicleRules.ReconstructionResult result = ChronicleRules.reconstruct(
                new ChronicleRules.ReconstructionInput(
                        List.of(new PlayerDeaths("u1", "Alice", false, deaths)), 30_000, null));
        assertTrue(result.dropped() > 0);
        assertEquals(ChronicleRules.DEFAULT_MAX_EVENTS, result.events().size());
        assertEquals(1, result.events().stream()
                .filter(e -> e.kind() == ChronicleEvent.Kind.SERVER_FIRST).count());
        assertEquals(ChronicleRules.DEFAULT_MAX_EVENTS - 1, result.events().stream()
                .filter(e -> e.kind() == ChronicleEvent.Kind.TOTAL_COUNT).count());
    }

    @Test
    void reconstructionHonorsTheCallersCapAndValidatesStoredEpochs() {
        // M1: a crafted counter near Integer.MAX_VALUE must not allocate a row
        // per hundred; the configured cap bounds generation itself.
        List<DeathEntry> deaths = List.of(death(1_000L, 1L, "minecraft:overworld", ""));
        ChronicleRules.ReconstructionResult result = ChronicleRules.reconstruct(
                new ChronicleRules.ReconstructionInput(
                        List.of(new PlayerDeaths("u1", "Alice", false, deaths)),
                        Integer.MAX_VALUE, null), 1_000);
        assertEquals(1_000, result.events().size());
        assertTrue(result.dropped() > Integer.MAX_VALUE / 200L);
        assertEquals(999, result.events().stream()
                .filter(ChronicleRules::isUndated).count());

        assertTrue(ChronicleRules.isValidEpoch(1L));
        assertFalse(ChronicleRules.isValidEpoch(0L));
        assertFalse(ChronicleRules.isValidEpoch(-1L));
    }

    @Test
    void publicRowsHideOptedOutNamesAndScrubSnapshotAliases() {
        PlayerDeaths hiddenPlayer = new PlayerDeaths("u-secret", "Secret", true,
                List.of(death(1L, 1L, "u-secret", "Alias Name", "minecraft:overworld", "")));
        Map<String, PlayerPrivacy> privacy = ChronicleRules.privacyById(List.of(hiddenPlayer));
        HiddenIdentities hidden = HiddenIdentities.of(List.of(hiddenPlayer));
        ChronicleEvent stored = new ChronicleEvent(1L, 1L, ChronicleEvent.Kind.SERVER_FIRST,
                "u-secret", "Secret", "minecraft:overworld", "fall", 1, false);
        ChronicleEvent alias = new ChronicleEvent(2L, 1L, ChronicleEvent.Kind.CATEGORY_COMPLETE,
                "u-other", "Alias Name was first", "", "", 17, false);
        List<ChronicleEvent> rows = ChronicleRules.forPublic(List.of(stored, alias), privacy, hidden, false);
        assertEquals(ChronicleRules.HIDDEN_NAME, rows.get(0).name());
        assertFalse(rows.get(1).name().contains("Alias"));
        assertEquals(stored, ChronicleRules.forPublic(stored, privacy, hidden, true));
        assertEquals("Secret", ChronicleRules.forPublic(stored, privacy, hidden, true).name());
        ChronicleEvent total = new ChronicleEvent(3L, 1L, ChronicleEvent.Kind.TOTAL_COUNT,
                "", "", "", "", 100, false);
        assertEquals("", ChronicleRules.forPublic(total, privacy, hidden, false).name());
    }

    @Test
    void discordRowsRespectDiscordOptOutAndKeepAnonymousTotals() {
        PlayerDeaths discordOptOut = new PlayerDeaths("u1", "Dana", false, false, false, true,
                List.of(death(1L, 1L, "u1", "Dana", "minecraft:overworld", "")));
        Map<String, PlayerPrivacy> privacy = ChronicleRules.privacyById(List.of(discordOptOut));
        ChronicleEvent named = new ChronicleEvent(1L, 1L, ChronicleEvent.Kind.NETHER_FIRST,
                "u1", "Dana", "minecraft:the_nether", "fall", 1, false);
        ChronicleEvent total = new ChronicleEvent(2L, 1L, ChronicleEvent.Kind.TOTAL_COUNT,
                "", "", "", "", 100, false);
        assertEquals(List.of(total), ChronicleRules.forDiscord(List.of(named, total), privacy));
    }

    @Test
    void rowsSortOldestFirstAndPageWithTenPerPage() {
        List<ChronicleEvent> events = new ArrayList<>();
        for (int i = 20; i >= 1; i--) {
            events.add(new ChronicleEvent(i, i, ChronicleEvent.Kind.TOTAL_COUNT,
                    "", "", "", "", i * 100, false));
        }
        List<ChronicleEvent> sorted = ChronicleRules.sorted(events);
        assertEquals(1L, sorted.get(0).epochMillis());
        Pagination.Page<ChronicleEvent> page = Pagination.of(sorted, 2, 10);
        assertEquals(2, page.page());
        assertEquals(2, page.pageCount());
        assertEquals(10, page.items().size());
        assertEquals(11L, page.items().get(0).epochMillis());
    }
}
