package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordLogicTest {

    private static DeathEntry entry(String name, long epoch, long day, int survival, String cause, String msg) {
        return new DeathEntry(
                "u-" + name, name, epoch, day, survival + 10, survival,
                "minecraft:overworld", 0, 64, 0,
                cause, "minecraft:" + cause, msg,
                "", "", "survival", 0, "", "", 0
        );
    }

    @Test
    void deathContentUsesMessageOrFallback() {
        DeathEntry withMsg = entry("Alice", 1000L, 12L, 72000 + 2400, "fall", "Alice fell from a high place");
        assertEquals(
                "Alice fell from a high place — Day 13 · minecraft:overworld · survived 1h 2m 0s",
                DiscordLogic.deathContent(withMsg)
        );

        DeathEntry blankMsg = entry("Bob", 1000L, 3L, 400, "lava", "   ");
        assertEquals(
                "Bob died (lava) — Day 4 · minecraft:overworld · survived 20s",
                DiscordLogic.deathContent(blankMsg)
        );
    }

    @Test
    void digestContentEmptyAndNonEmpty() {
        long since = Instant.parse("2026-11-20T00:00:00Z").toEpochMilli();
        long before = Instant.parse("2026-11-19T12:00:00Z").toEpochMilli();
        long after1 = Instant.parse("2026-11-21T00:00:00Z").toEpochMilli();
        long after2 = Instant.parse("2026-11-22T00:00:00Z").toEpochMilli();

        PlayerDeaths onlyOld = new PlayerDeaths("u1", "Alice", false, List.of(
                entry("Alice", before, 1L, 100, "fall", "Alice fell")
        ));
        assertEquals("", DiscordLogic.digestContent(List.of(onlyOld), since));
        assertEquals("", DiscordLogic.digestContent(List.of(), since));

        List<PlayerDeaths> players = List.of(
                new PlayerDeaths("u1", "Alice", false, List.of(
                        entry("Alice", before, 1L, 100, "void", "old"),
                        entry("Alice", after1, 2L, 100, "lava", "m1"),
                        entry("Alice", after2, 3L, 200, "lava", "m2")
                )),
                new PlayerDeaths("u2", "Bob", false, List.of(
                        entry("Bob", after1, 2L, 100, "fall", "m3")
                ))
        );

        String digest = DiscordLogic.digestContent(players, since);
        String[] lines = digest.split("\n");
        assertEquals(3, lines.length);
        assertEquals("Death Census digest: 3 death(s) since 2026-11-20", lines[0]);
        assertEquals("Top causes: lava x2, fall x1", lines[1]);
        assertEquals("Most deaths: Alice (2), Bob (1)", lines[2]);
    }

    @Test
    void digestCountsOptedOutDeathsWithoutNamingThem() {
        long since = Instant.parse("2026-11-20T00:00:00Z").toEpochMilli();
        long after1 = Instant.parse("2026-11-21T00:00:00Z").toEpochMilli();
        long after2 = Instant.parse("2026-11-22T00:00:00Z").toEpochMilli();

        List<PlayerDeaths> players = List.of(
                new PlayerDeaths("u1", "Alice", false, List.of(
                        entry("Alice", after1, 2L, 100, "lava", "m1")
                )),
                new PlayerDeaths("u2", "Eve", true, List.of(
                        entry("Eve", after1, 2L, 100, "lava", "m2"),
                        entry("Eve", after2, 3L, 100, "lava", "m3"),
                        entry("Eve", after2, 3L, 100, "fall", "m4")
                ))
        );

        String digest = DiscordLogic.digestContent(players, since);
        String[] lines = digest.split("\n");
        assertEquals(3, lines.length);
        assertEquals("Death Census digest: 4 death(s) since 2026-11-20", lines[0]);
        assertEquals("Top causes: lava x3, fall x1", lines[1]);
        assertEquals("Most deaths: Alice (1)", lines[2]);
        assertFalse(digest.contains("Eve"));
    }

    @Test
    void digestOmitsMostDeathsLineWhenEveryoneOptedOut() {
        long since = Instant.parse("2026-11-20T00:00:00Z").toEpochMilli();
        long after1 = Instant.parse("2026-11-21T00:00:00Z").toEpochMilli();
        long after2 = Instant.parse("2026-11-22T00:00:00Z").toEpochMilli();

        List<PlayerDeaths> players = List.of(
                new PlayerDeaths("u1", "Eve", true, List.of(
                        entry("Eve", after1, 2L, 100, "lava", "m1"),
                        entry("Eve", after2, 3L, 100, "lava", "m2")
                )),
                new PlayerDeaths("u2", "Mallory", true, List.of(
                        entry("Mallory", after1, 2L, 100, "fall", "m3")
                ))
        );

        String digest = DiscordLogic.digestContent(players, since);
        String[] lines = digest.split("\n");
        assertEquals(2, lines.length);
        assertEquals("Death Census digest: 3 death(s) since 2026-11-20", lines[0]);
        assertEquals("Top causes: lava x2, fall x1", lines[1]);
        assertFalse(digest.contains("Eve"));
        assertFalse(digest.contains("Mallory"));
    }

    @Test
    void digestHonorsJapaneseLabelsAndConfiguredZone() {
        long since = Instant.parse("2026-11-20T20:00:00Z").toEpochMilli();
        long after = Instant.parse("2026-11-20T21:00:00Z").toEpochMilli();

        List<PlayerDeaths> players = List.of(
                new PlayerDeaths("u1", "Alice", false, List.of(
                        entry("Alice", after, 2L, 100, "lava", "m1")
                ))
        );

        assertEquals(DiscordLogic.labelsFor("ja-JP"), DiscordLogic.labelsFor("ja_jp"));
        assertEquals(
                "死亡センサス ダイジェスト: 1 件の死亡（以降） 2026-11-21\n"
                        + "主な死因: lava x1\n"
                        + "最多死亡: Alice (1)",
                DiscordLogic.digestContent(
                        players, since, ZoneId.of("Asia/Tokyo"), DiscordLogic.labelsFor("ja_jp"))
        );
    }

    @Test
    void webhookUrlValidation() {
        assertTrue(DiscordLogic.isValidWebhookUrl("https://discord.com/api/webhooks/123456/abcdef"));
        assertTrue(DiscordLogic.isValidWebhookUrl("https://discordapp.com/api/webhooks/123456/abcdef"));
        assertFalse(DiscordLogic.isValidWebhookUrl("https://discord.com/api/webhooks/"));
        assertFalse(DiscordLogic.isValidWebhookUrl("https://discord.com/api/webhooks/   "));
        assertFalse(DiscordLogic.isValidWebhookUrl("https://discord.com/api/webhooks/123 456"));
        assertFalse(DiscordLogic.isValidWebhookUrl("http://discord.com/api/webhooks/123/abc"));
        assertFalse(DiscordLogic.isValidWebhookUrl("https://example.com/api/webhooks/123/abc"));
        assertFalse(DiscordLogic.isValidWebhookUrl(null));
    }

    @Test
    void resolveWebhookUrlHandlesPlainAndEnv() {
        assertEquals("", DiscordLogic.resolveWebhookUrl(null));
        assertEquals("", DiscordLogic.resolveWebhookUrl("   "));
        assertEquals(
                "https://discord.com/api/webhooks/1/a",
                DiscordLogic.resolveWebhookUrl("  https://discord.com/api/webhooks/1/a  ")
        );
        assertEquals(
                "",
                DiscordLogic.resolveWebhookUrl("env:DEATH_CHRONICLE_DEFINITELY_UNSET_VAR_987654321")
        );
        assertEquals("", DiscordLogic.resolveWebhookUrl("env:"));
    }
}
