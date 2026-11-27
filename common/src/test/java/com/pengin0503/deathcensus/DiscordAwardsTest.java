package com.pengin0503.deathcensus;

import com.pengin0503.deathcensus.DiscordLogic.SeasonAwards;
import com.pengin0503.deathcensus.DiscordLogic.SeasonSection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.4.0 §2: opt-in award sections in the digest and season post only. */
class DiscordAwardsTest {
    private static final long FROM = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    private static final long TO = Instant.parse("2026-01-03T00:00:00Z").toEpochMilli();

    private static DeathEntry death(String name, long epoch) {
        return new DeathEntry("u-" + name, name, epoch, 1L, 100, 100,
                "d", 0, 0, 0, "fall", "minecraft:fall", "", "", "", "survival", 0,
                "", "", 0);
    }

    private static PlayerDeaths player(String name, boolean optedOut, DeathEntry... deaths) {
        return new PlayerDeaths("u-" + name, name, optedOut, List.of(deaths));
    }

    @Test
    void legacyThreeArgumentAwardsPreserveTheOriginalSeasonSections() {
        List<PlayerDeaths> players = List.of(player("Alice", false, death("Alice", FROM + 1)));
        List<SeasonSection> sections = DiscordLogic.seasonSections(
                players, new SeasonRange(FROM, TO), ZoneOffset.UTC,
                DiscordLogic.labelsFor("en_us"), new SeasonAwards(List.of(), null, ""));
        assertEquals(4, sections.size());
        assertFalse(sections.stream().anyMatch(section -> section.label().startsWith("Titles")));
    }

    @Test
    void awardLinesBecomeTheirOwnSeasonSectionAndOptedOutNamesAreScrubbed() {
        List<PlayerDeaths> players = List.of(
                player("Alice", false, death("Alice", FROM + 1)),
                player("Hidden", true, death("Hidden", FROM + 2)));
        SeasonAwards awards = new SeasonAwards(List.of(), null, "",
                List.of("Most deaths: Alice — 1", "Longest survival: Hidden — 100"), "Titles");
        String content = DiscordLogic.seasonContent(
                players, new SeasonRange(FROM, TO), ZoneOffset.UTC,
                DiscordLogic.labelsFor("en_us"), awards, DiscordLogic.SeasonLabels.english());
        assertTrue(content.contains("Titles: Most deaths: Alice — 1"));
        assertFalse(content.contains("Hidden"));
        assertEquals(5, DiscordLogic.seasonSections(
                players, new SeasonRange(FROM, TO), ZoneOffset.UTC,
                DiscordLogic.labelsFor("en_us"), awards).size());
    }

    @Test
    void digestAwardsAreAppendOnlyAndDisabledByDefault() {
        List<PlayerDeaths> players = List.of(player("Alice", false, death("Alice", FROM + 1)));
        String plain = DiscordLogic.digestContent(players, FROM - 1, ZoneOffset.UTC,
                DiscordLogic.labelsFor("en_us"));
        assertFalse(plain.contains("Titles"));
        String withAwards = DiscordLogic.digestContent(players, FROM - 1, ZoneOffset.UTC,
                DiscordLogic.labelsFor("en_us"), List.of("Most deaths: Alice — 1"), "Titles");
        assertTrue(withAwards.startsWith(plain));
        assertTrue(withAwards.contains("\nTitles: Most deaths: Alice — 1"));
    }
}
