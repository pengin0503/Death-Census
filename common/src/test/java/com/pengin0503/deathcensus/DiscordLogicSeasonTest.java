package com.pengin0503.deathcensus;

import com.pengin0503.deathcensus.DiscordLogic.SeasonAwards;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure season-digest tests: range filtering, awards and DESIGN §8 privacy. */
class DiscordLogicSeasonTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final DiscordLogic.DigestLabels EN = DiscordLogic.labelsFor("en_us");

    private static long epoch(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    private static DeathEntry death(String name, long epoch, String cause, String lastWords, int fCount) {
        return new DeathEntry("u-" + name, name, epoch, 1L, 120, 100, "minecraft:overworld",
                0, 64, 0, cause, "minecraft:" + cause, name + " died",
                "", "", "survival", 0, "", lastWords, fCount);
    }

    private static PlayerDeaths player(String name, boolean optedOut, DeathEntry... deaths) {
        return new PlayerDeaths("u-" + name, name, optedOut, List.of(deaths));
    }

    @Test
    void seasonContentFiltersInclusiveBoundsAndRendersAllSections() {
        long from = epoch("2026-01-01T00:00:00Z");
        long inside = epoch("2026-01-02T12:00:00Z");
        long to = epoch("2026-01-03T00:00:00Z");
        SeasonRange range = new SeasonRange(from, to);
        List<PlayerDeaths> players = List.of(
                player("Alice", false,
                        death("Alice", from, "lava", "", 0),
                        death("Alice", inside, "lava", "gg", 2),
                        death("Alice", to, "fall", "hi", 1),
                        death("Alice", epoch("2026-01-04T00:00:00Z"), "void", "OUT", 9)),
                player("Bob", false, death("Bob", inside, "fall", "", 0)),
                player("Carol", false, death("Carol", epoch("2025-12-31T23:59:59Z"),
                        "magic", "OLD", 5)));

        String content = DiscordLogic.seasonContent(players, range, UTC, EN, SeasonAwards.none());

        assertEquals(String.join("\n",
                "Season digest: 4 death(s), 2026-01-01 → 2026-01-03",
                "Top causes: fall x2, lava x2",
                "Most deaths: Alice (3), Bob (1)",
                "Most-respected last words (by F count): Alice: \"gg\" (F x2); Alice: \"hi\" (F x1)",
                "Awards: All 17 categories: none (0 qualifiers); Longest survival: none; Rarest cause: none"),
                content);
        assertFalse(content.contains("OUT"));
        assertFalse(content.contains("OLD"));
        assertEquals(5, DiscordLogic.seasonSections(players, range, UTC, EN, SeasonAwards.none()).size());
    }

    @Test
    void optedOutDeathsCountAnonymouslyAndNeverAppearByName() {
        long from = epoch("2026-01-01T00:00:00Z");
        long to = epoch("2026-01-03T00:00:00Z");
        DeathEntry eveLongest = death("Eve", epoch("2026-01-02T10:00:00Z"), "lava", "Eve's secret", 7);
        List<PlayerDeaths> players = List.of(
                player("Alice", false, death("Alice", epoch("2026-01-01T12:00:00Z"), "lava", "Alice was here", 0)),
                player("Bob", false, death("Bob", epoch("2026-01-02T20:00:00Z"), "fall", "Eve was here", 0)),
                player("Eve", true,
                        death("Eve", epoch("2026-01-01T18:00:00Z"), "lava", "", 0),
                        eveLongest,
                        death("Eve", epoch("2026-01-02T22:00:00Z"), "fall", "", 0)));
        SeasonAwards awards = new SeasonAwards(List.of("Eve", "Alice"), eveLongest, "lava");

        String content = DiscordLogic.seasonContent(
                players, new SeasonRange(from, to), UTC, EN, awards);

        assertTrue(content.startsWith("Season digest: 5 death(s), 2026-01-01 → 2026-01-03"));
        assertTrue(content.contains("Top causes: lava x3, fall x2"));
        assertTrue(content.contains("Most deaths: Alice (1), Bob (1)"));
        // Eve's own last words are excluded entirely; a hidden name inside a
        // public player's free text is scrubbed rather than echoed.
        assertFalse(content.contains("Eve"));
        assertTrue(content.contains("Bob: \"(hidden) was here\""));
        assertTrue(content.contains("All 17 categories: Alice (1 qualifiers)"));
        assertTrue(content.contains("Longest survival: none"));
        assertTrue(content.contains("Rarest cause: lava"));
    }

    @Test
    void emptyPeriodReturnsNoContentAndNoSections() {
        SeasonRange range = new SeasonRange(epoch("2026-01-01T00:00:00Z"), epoch("2026-01-02T00:00:00Z"));
        List<PlayerDeaths> players = List.of(player("Alice", false,
                death("Alice", epoch("2025-12-31T00:00:00Z"), "fall", "", 0)));

        assertEquals("", DiscordLogic.seasonContent(players, range, UTC, EN, SeasonAwards.none()));
        assertTrue(DiscordLogic.seasonSections(players, range, UTC, EN, SeasonAwards.none()).isEmpty());
        assertTrue(DiscordLogic.seasonContent(null, range, UTC, EN, SeasonAwards.none()).isEmpty());
    }

    @Test
    void lastWordsAreRankedByFCountThenNewestEpoch() {
        long from = epoch("2026-01-01T00:00:00Z");
        long to = epoch("2026-01-04T00:00:00Z");
        List<PlayerDeaths> players = List.of(player("Alice", false,
                death("Alice", epoch("2026-01-01T10:00:00Z"), "fall", "old-high", 3),
                death("Alice", epoch("2026-01-02T10:00:00Z"), "fall", "new-high", 3),
                death("Alice", epoch("2026-01-03T10:00:00Z"), "fall", "low", 2)));

        String content = DiscordLogic.seasonContent(
                players, new SeasonRange(from, to), UTC, EN, SeasonAwards.none());

        assertTrue(content.contains("Most-respected last words (by F count): "
                + "Alice: \"new-high\" (F x3); Alice: \"old-high\" (F x3); Alice: \"low\" (F x2)"));
    }

    @Test
    void awardsRenderOneAndManyQualifiers() {
        long from = epoch("2026-01-01T00:00:00Z");
        long to = epoch("2026-01-02T00:00:00Z");
        DeathEntry longest = death("Alice", epoch("2026-01-01T12:00:00Z"), "lava", "", 0);
        List<PlayerDeaths> players = List.of(
                player("Alice", false, longest),
                player("Bob", false, death("Bob", epoch("2026-01-01T13:00:00Z"), "fall", "", 0)));
        SeasonAwards awards = new SeasonAwards(
                java.util.Arrays.asList("Alice", "Bob", "  ", null), longest, "lava");

        String content = DiscordLogic.seasonContent(players, new SeasonRange(from, to), UTC, EN, awards);

        assertTrue(content.contains("All 17 categories: Alice, Bob (2 qualifiers)"));
        assertTrue(content.contains("Longest survival: Alice (5s)"));
        assertTrue(content.contains("Rarest cause: lava"));

        String single = DiscordLogic.seasonContent(players, new SeasonRange(from, to), UTC, EN,
                new SeasonAwards(List.of("Bob"), null, ""));
        assertTrue(single.contains("All 17 categories: Bob (1 qualifiers)"));
        assertTrue(single.contains("Longest survival: none"));
        assertTrue(single.contains("Rarest cause: none"));
    }

    @Test
    void japaneseLabelsRenderInConfiguredLanguageAndHaveParity() {
        long from = epoch("2026-01-01T00:00:00Z");
        long to = epoch("2026-01-02T00:00:00Z");
        List<PlayerDeaths> players = List.of(player("Alice", false,
                death("Alice", epoch("2026-01-01T12:00:00Z"), "lava", "gg", 1)));
        SeasonRange range = new SeasonRange(from, to);

        String content = DiscordLogic.seasonContent(players, range, UTC,
                DiscordLogic.labelsFor("ja_jp"), SeasonAwards.none(), DiscordLogic.SeasonLabels.japanese());

        assertTrue(content.startsWith("シーズンダイジェスト: 1 件の死亡（2026-01-01 → 2026-01-02）"));
        assertTrue(content.contains("主な死因: lava x1"));
        assertTrue(content.contains("最多死亡: Alice (1)"));
        assertTrue(content.contains("印象的な最期の言葉"));
        assertTrue(content.contains("表彰: "));

        assertEquals(DiscordLogic.SeasonLabels.class.getRecordComponents().length,
                DiscordLogic.SeasonLabels.english().getClass().getRecordComponents().length);
        for (var component : DiscordLogic.SeasonLabels.class.getRecordComponents()) {
            assertTrue(readLabel(DiscordLogic.SeasonLabels.english(), component.getName()) != null);
            assertFalse(readLabel(DiscordLogic.SeasonLabels.japanese(), component.getName()).isEmpty());
        }
    }

    private static String readLabel(DiscordLogic.SeasonLabels labels, String componentName) {
        return switch (componentName) {
            case "header" -> labels.header();
            case "headerField" -> labels.headerField();
            case "lastWords" -> labels.lastWords();
            case "awards" -> labels.awards();
            case "allCategories" -> labels.allCategories();
            case "longestSurvival" -> labels.longestSurvival();
            case "rarestCause" -> labels.rarestCause();
            case "none" -> labels.none();
            default -> throw new IllegalArgumentException(componentName);
        };
    }
}
