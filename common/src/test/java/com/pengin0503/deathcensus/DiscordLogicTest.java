package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    private static DeathEntry death(String dimension, String causeCategory, String killerType) {
        return new DeathEntry(
                "u-Alice", "Alice", 1000L, 1L, 110, 100, dimension, 0, 64, 0,
                causeCategory, "test:" + causeCategory, "Alice died",
                killerType, "Bob", "survival", 0, "", "", 0
        );
    }

    @Test
    void shouldNotifyAllowsAllWhenDimensionListIsEmptyOrNull() {
        DeathEntry nether = death("minecraft:the_nether", "fall", "");
        assertTrue(DiscordLogic.shouldNotify(nether, null, DiscordLogic.PvpFilterMode.ALL));
        assertTrue(DiscordLogic.shouldNotify(nether, List.of(), DiscordLogic.PvpFilterMode.ALL));
        assertFalse(DiscordLogic.shouldNotify(nether, List.of(), DiscordLogic.PvpFilterMode.PVP_ONLY));
        assertTrue(DiscordLogic.shouldNotify(death("minecraft:the_nether", "player", "minecraft:player"),
                List.of(), DiscordLogic.PvpFilterMode.PVP_ONLY));
        assertTrue(DiscordLogic.shouldNotify(nether, List.of(), DiscordLogic.PvpFilterMode.OFF));
    }

    @Test
    void shouldNotifyMatchesDimensionExactlyAndCaseSensitively() {
        DeathEntry end = death("minecraft:the_end", "fall", "");
        assertTrue(DiscordLogic.shouldNotify(end, List.of("minecraft:the_end"), DiscordLogic.PvpFilterMode.ALL));
        assertTrue(DiscordLogic.shouldNotify(end,
                List.of("minecraft:overworld", "minecraft:the_end"), DiscordLogic.PvpFilterMode.ALL));
        assertFalse(DiscordLogic.shouldNotify(end, List.of("minecraft:overworld"), DiscordLogic.PvpFilterMode.ALL));
        assertFalse(DiscordLogic.shouldNotify(end, List.of("MINECRAFT:THE_END"), DiscordLogic.PvpFilterMode.ALL));
        assertFalse(DiscordLogic.shouldNotify(end, List.of("the_end"), DiscordLogic.PvpFilterMode.ALL));
    }

    @Test
    void shouldNotifyUsesCauseCategoryOnlyForPvpModes() {
        DeathEntry player = death("minecraft:overworld", "player", "minecraft:player");
        DeathEntry indirectPlayer = death("minecraft:overworld", "player", "minecraft:arrow");
        DeathEntry fall = death("minecraft:overworld", "fall", "");

        assertTrue(DiscordLogic.shouldNotify(player, null, DiscordLogic.PvpFilterMode.PVP_ONLY));
        assertTrue(DiscordLogic.shouldNotify(indirectPlayer, null, DiscordLogic.PvpFilterMode.PVP_ONLY));
        assertFalse(DiscordLogic.shouldNotify(fall, null, DiscordLogic.PvpFilterMode.PVP_ONLY));

        assertFalse(DiscordLogic.shouldNotify(player, null, DiscordLogic.PvpFilterMode.OFF));
        assertFalse(DiscordLogic.shouldNotify(indirectPlayer, null, DiscordLogic.PvpFilterMode.OFF));
        assertTrue(DiscordLogic.shouldNotify(fall, null, DiscordLogic.PvpFilterMode.OFF));

        assertTrue(DiscordLogic.shouldNotify(player, null, DiscordLogic.PvpFilterMode.ALL));
        assertTrue(DiscordLogic.shouldNotify(fall, null, DiscordLogic.PvpFilterMode.ALL));
    }

    @Test
    void shouldNotifyCombinesDimensionAndPvpFiltersWithAnd() {
        List<String> allowed = List.of("minecraft:overworld");
        assertTrue(DiscordLogic.shouldNotify(
                death("minecraft:overworld", "player", "minecraft:player"), allowed,
                DiscordLogic.PvpFilterMode.PVP_ONLY));
        assertFalse(DiscordLogic.shouldNotify(
                death("minecraft:the_nether", "player", "minecraft:player"), allowed,
                DiscordLogic.PvpFilterMode.PVP_ONLY));
        assertFalse(DiscordLogic.shouldNotify(
                death("minecraft:overworld", "fall", ""), allowed,
                DiscordLogic.PvpFilterMode.PVP_ONLY));
        assertFalse(DiscordLogic.shouldNotify(
                death("minecraft:the_nether", "fall", ""), allowed,
                DiscordLogic.PvpFilterMode.OFF));
        assertTrue(DiscordLogic.shouldNotify(
                death("minecraft:overworld", "fall", ""), allowed,
                DiscordLogic.PvpFilterMode.OFF));
    }

    @Test
    void shouldNotifyIsNullSafeAndIgnoresOptOutSnapshot() {
        assertFalse(DiscordLogic.shouldNotify(null, null, DiscordLogic.PvpFilterMode.ALL));
        assertFalse(DiscordLogic.shouldNotify(null, List.of("minecraft:overworld"),
                DiscordLogic.PvpFilterMode.PVP_ONLY));
        DeathEntry entry = death("minecraft:overworld", "player", "minecraft:player");
        assertTrue(DiscordLogic.shouldNotify(entry, null, null));

        // The §8 opt-out flag lives on the snapshot, not the entry; the filter
        // predicate never consults it (DeathTracker/notifyDeath still do).
        PlayerDeaths optedOut = new PlayerDeaths("u-Alice", "Alice", true, List.of(entry));
        assertTrue(DiscordLogic.shouldNotify(optedOut.deaths().get(0), null,
                DiscordLogic.PvpFilterMode.ALL));
        assertTrue(DiscordLogic.shouldNotify(optedOut.deaths().get(0), null,
                DiscordLogic.PvpFilterMode.PVP_ONLY));
    }

    @Test
    void digestCountsAllDeathsAndNamesOnlyPlayersWithoutFullOptOut() {
        long since = 1_000L;
        DeathEntry a = entry("Alice", 1_500L, 1L, 100, "fall", "Alice fell");
        DeathEntry b = entry("Bob", 1_600L, 1L, 100, "fall", "Bob fell");
        DeathEntry c = entry("Dana", 1_700L, 1L, 100, "fall", "Dana fell");
        List<PlayerDeaths> players = List.of(
                new PlayerDeaths("u-Alice", "Alice", false, true, false, false, List.of(a)),
                new PlayerDeaths("u-Bob", "Bob", false, false, false, true, List.of(b)),
                new PlayerDeaths("u-Dana", "Dana", true, true, true, true, List.of(c)));

        String content = DiscordLogic.digestContent(
                players, since, java.time.ZoneOffset.UTC, DiscordLogic.labelsFor("en_us"));

        assertTrue(content.contains("3 death(s) since"));
        assertTrue(content.contains("Top causes: fall x3"));
        assertTrue(content.contains("Most deaths: Alice (1), Bob (1)"));
        assertFalse(content.contains("Dana"));
        assertTrue(new PlayerPrivacy(false, false, false, true).suppressesRealtimeDiscord());
        assertFalse(PlayerPrivacy.of(new PlayerDeaths("u", "U", false, List.of()))
                .suppressesRealtimeDiscord());
    }

    @Test
    void pvpFilterModeConfigValuesRoundTrip() {
        assertEquals("all", DiscordLogic.PvpFilterMode.ALL.configId());
        assertEquals("pvp_only", DiscordLogic.PvpFilterMode.PVP_ONLY.configId());
        assertEquals("off", DiscordLogic.PvpFilterMode.OFF.configId());
        assertEquals(DiscordLogic.PvpFilterMode.ALL, DiscordLogic.PvpFilterMode.fromConfigValue("all"));
        assertEquals(DiscordLogic.PvpFilterMode.PVP_ONLY,
                DiscordLogic.PvpFilterMode.fromConfigValue("pvp_only"));
        assertEquals(DiscordLogic.PvpFilterMode.OFF, DiscordLogic.PvpFilterMode.fromConfigValue("off"));
        assertNull(DiscordLogic.PvpFilterMode.fromConfigValue("ALL"));
        assertNull(DiscordLogic.PvpFilterMode.fromConfigValue("pvp"));
        assertNull(DiscordLogic.PvpFilterMode.fromConfigValue(null));
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

    @Test
    void channelResolutionUsesIndependentValues() {
        String primary = "https://discord.com/api/webhooks/111/aaa";
        String digest = "https://discord.com/api/webhooks/222/bbb";

        assertEquals(primary, DiscordLogic.resolveWebhookUrl(WebhookChannel.REALTIME, primary, digest));
        assertEquals(digest, DiscordLogic.resolveWebhookUrl(WebhookChannel.DIGEST, primary, digest));

        // Empty digest (including an unset env var) falls back to the primary URL.
        assertEquals(primary, DiscordLogic.resolveWebhookUrl(WebhookChannel.DIGEST, primary, ""));
        assertEquals(primary, DiscordLogic.resolveWebhookUrl(WebhookChannel.DIGEST, primary, "   "));
        assertEquals(
                primary,
                DiscordLogic.resolveWebhookUrl(
                        WebhookChannel.DIGEST, primary, "env:DEATH_CENSUS_DEFINITELY_UNSET_VAR_987654321"));
        // env: works on the digest value too; here PATH is set but is not a webhook URL,
        // which must be returned as-is (invalid), not replaced by the primary URL.
        String path = System.getenv("PATH");
        if (path != null && !path.isBlank()) {
            assertEquals(path.trim(), DiscordLogic.resolveWebhookUrl(WebhookChannel.DIGEST, primary, "env:PATH"));
        }

        // A set-but-invalid digest URL must NOT silently fall back (§12 Q9).
        String typo = "https://example.com/not-a-webhook";
        assertEquals(typo, DiscordLogic.resolveWebhookUrl(WebhookChannel.DIGEST, primary, typo));
        assertFalse(DiscordLogic.isValidWebhookUrl(
                DiscordLogic.resolveWebhookUrl(WebhookChannel.DIGEST, primary, typo)));
        // The realtime channel is unaffected by any digest value.
        assertEquals(primary, DiscordLogic.resolveWebhookUrl(WebhookChannel.REALTIME, primary, typo));
    }
}
