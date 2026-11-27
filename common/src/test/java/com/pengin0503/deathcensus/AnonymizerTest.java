package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnonymizerTest {
    private static final long T = Instant.parse("2026-11-24T10:15:00Z").toEpochMilli();
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static DeathEntry death(String uuid, String name, double x, double y, double z, String message,
                                    String killerName, String excuse, String lastWords) {
        return new DeathEntry(uuid, name, T, 3L, 1000, 900, "minecraft:overworld", x, y, z,
                "player", "minecraft:player", message, "minecraft:player", killerName,
                "survival", 7, excuse, lastWords, 4);
    }

    private static void assertNoLeaks(String rendered, String... secrets) {
        for (String secret : secrets) {
            assertFalse(rendered.contains(secret), "leaked '" + secret + "' in: " + rendered);
        }
        assertFalse(UUID_PATTERN.matcher(rendered).find(), "leaked a UUID in: " + rendered);
    }

    @Test
    void pseudonymsFollowUuidOrderAndAreStableAcrossFormats() {
        String alpha = "00000000-0000-0000-0000-00000000000a";
        String beta = "00000000-0000-0000-0000-00000000000b";
        // Names intentionally sort the opposite way from the UUIDs.
        DeathEntry zedDeath = death(alpha, "Zed", 130.0, -130.0, 96.0,
                "Zed was slain by Beta, then " + alpha + " laughed",
                "Beta", "Beta did it", "Goodbye " + beta);
        DeathEntry betaDeath = death(beta, "Beta", 1.0, 2.0, 3.0,
                "Beta fell after Zed knocked them", "Zed", "", "");
        List<PlayerDeaths> original = List.of(
                new PlayerDeaths(beta, "Beta", false, List.of(betaDeath)),
                new PlayerDeaths(alpha, "Zed", false, List.of(zedDeath)));

        List<PlayerDeaths> redacted = Anonymizer.apply(original);
        assertEquals(2, redacted.size());
        assertEquals("Player A", redacted.get(0).name()); // Zed has the lower UUID
        assertEquals("anon-1", redacted.get(0).uuid());
        assertEquals("Player B", redacted.get(1).name());
        assertEquals("anon-2", redacted.get(1).uuid());
        assertEquals("Player A was slain by Player B, then anon-1 laughed",
                redacted.get(0).deaths().get(0).deathMessage());
        assertEquals("Player B", redacted.get(0).deaths().get(0).killerName());
        assertEquals("Player B did it", redacted.get(0).deaths().get(0).excuse());
        assertEquals("Goodbye anon-2", redacted.get(0).deaths().get(0).lastWords());
        assertEquals("Player B fell after Player A knocked them",
                redacted.get(1).deaths().get(0).deathMessage());
        assertNotSame(original.get(0), redacted.get(0));
        assertEquals("Beta", original.get(0).name()); // copy, not an in-place edit

        String csv = ExportRenderers.toCsv(original, true);
        String json = ExportRenderers.toJson(original, T, true);
        String html = ExportRenderers.toHtml(original, T, ZoneOffset.UTC, true);
        for (String rendered : List.of(csv, json, html)) {
            assertTrue(rendered.contains("Player A"), rendered);
            assertTrue(rendered.contains("Player B"), rendered);
            assertTrue(rendered.contains("Player A was slain by Player B"), rendered);
            assertNoLeaks(rendered, alpha, beta, "Zed", "Beta");
        }
    }

    @Test
    void freeTextUsesLongestNamesFirstAndWordBoundaries() {
        String a = "00000000-0000-0000-0000-000000000001";
        String b = "00000000-0000-0000-0000-000000000002";
        String c = "00000000-0000-0000-0000-000000000003";
        DeathEntry d = death(a, "Alice", 0, 0, 0,
                "Alice and Alice, Jr. and Alice, Junior and Bobcat and Bob",
                "Bob the zombie", "It was Alice, Jr.'s fault", "Bye Alice");
        List<PlayerDeaths> players = List.of(
                new PlayerDeaths(a, "Alice", false, List.of(d)),
                new PlayerDeaths(b, "Alice, Jr.", false, List.of()),
                new PlayerDeaths(c, "Bob", false, List.of()));

        DeathEntry redacted = Anonymizer.apply(players).get(0).deaths().get(0);
        assertEquals("Player A and Player B and Player A, Junior and Bobcat and Player C",
                redacted.deathMessage());
        assertEquals("Player C the zombie", redacted.killerName());
        assertEquals("It was Player B's fault", redacted.excuse());
        assertEquals("Bye Player A", redacted.lastWords());
    }

    @Test
    void coordinatesRoundToNearest64WithTiesTowardZero() {
        String uuid = "00000000-0000-0000-0000-00000000000c";
        DeathEntry d = death(uuid, "Steve", 130.0, -130.0, 96.0, "fell", "", "", "");
        DeathEntry d2 = death(uuid, "Steve", 31.0, -31.0, 0.0, "fell", "", "", "");
        List<PlayerDeaths> players = List.of(new PlayerDeaths(uuid, "Steve", false, List.of(d, d2)));

        List<DeathEntry> redacted = Anonymizer.apply(players).get(0).deaths();
        assertEquals(128.0, redacted.get(0).x());
        assertEquals(-128.0, redacted.get(0).y());
        assertEquals(64.0, redacted.get(0).z());  // exact half rounds toward zero
        assertEquals(0.0, redacted.get(1).x());
        assertEquals(0.0, redacted.get(1).y());
        assertEquals(0.0, redacted.get(1).z());

        String json = ExportRenderers.toJson(players, T, true);
        assertTrue(json.contains("\"x\": 128.0"));
        assertTrue(json.contains("\"y\": -128.0"));
        assertTrue(json.contains("\"z\": 64.0"));
        String csv = ExportRenderers.toCsv(players, true);
        assertTrue(csv.contains(",128.0,-128.0,64.0,"));
    }

    @Test
    void nullEmptyAndNullEntriesAreSafe() {
        assertTrue(Anonymizer.apply(null).isEmpty());
        assertTrue(Anonymizer.apply(List.of()).isEmpty());
        assertTrue(Anonymizer.apply(Arrays.asList(null, null)).isEmpty());

        String uuid = "00000000-0000-0000-0000-00000000000d";
        List<PlayerDeaths> mixed = Arrays.asList(null,
                new PlayerDeaths(uuid, "Solo", false, List.of(death(uuid, "Solo", 0, 0, 0, "Solo fell", "", "", ""))));
        List<PlayerDeaths> redacted = Anonymizer.apply(mixed);
        assertEquals(1, redacted.size());
        assertEquals("Player A", redacted.get(0).name());
        assertEquals("Player A fell", redacted.get(0).deaths().get(0).deathMessage());

        assertTrue(ExportRenderers.toJson(null, T, true).contains("\"anonymized\": true"));
        assertTrue(ExportRenderers.toCsv(null, true).startsWith("player_uuid,player_name,"));
        assertTrue(ExportRenderers.toHtml(null, T, ZoneOffset.UTC, true).contains("<html"));
    }

    @Test
    void moreThan26PlayersUseDoubleLetterPseudonyms() {
        List<PlayerDeaths> players = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String uuid = String.format(Locale.ROOT, "00000000-0000-0000-0000-%012d", i);
            players.add(new PlayerDeaths(uuid, "Name" + i, false, List.of()));
        }
        List<PlayerDeaths> redacted = Anonymizer.apply(players);
        assertEquals("Player Z", redacted.get(25).name());
        assertEquals("anon-26", redacted.get(25).uuid());
        assertEquals("Player AA", redacted.get(26).name());
        assertEquals("anon-27", redacted.get(26).uuid());
        assertEquals("Player AB", redacted.get(27).name());
        assertEquals("Player AD", redacted.get(29).name());
    }

    @Test
    void jsonMetadataAndOptOutNormalizationDoNotChangeNormalExports() {
        String uuid = "00000000-0000-0000-0000-00000000000e";
        PlayerDeaths optedOut = new PlayerDeaths(uuid, "Hidden", true,
                List.of(death(uuid, "Hidden", 1, 2, 3, "Hidden fell", "", "", "")));
        List<PlayerDeaths> players = List.of(optedOut);

        String normalJson = ExportRenderers.toJson(players, T);
        assertEquals(normalJson, ExportRenderers.toJson(players, T, false));
        assertEquals(ExportRenderers.toCsv(players), ExportRenderers.toCsv(players, false));
        assertEquals(ExportRenderers.toHtml(players, T, ZoneOffset.UTC),
                ExportRenderers.toHtml(players, T, ZoneOffset.UTC, false));
        assertTrue(normalJson.contains("\"optOutPublic\": true"));
        assertFalse(normalJson.contains("\"anonymized\""));

        String anonJson = ExportRenderers.toJson(players, T, true);
        assertTrue(anonJson.contains("\"anonymized\": true"));
        assertTrue(anonJson.contains("\"optOutPublic\": false"));
        assertFalse(anonJson.contains("\"optOutPublic\": true"));
        assertEquals(false, Anonymizer.apply(players).get(0).optOutPublic());
        assertNoLeaks(anonJson, uuid, "Hidden");
        assertEquals("Hidden", optedOut.name());
    }

    @Test
    void dictionaryUsesFullSnapshotNotTheFilteredExport() {
        // Review S4: Ghost opted out and is excluded from the export, and Bobby
        // is only a recorded alias. Neither may survive in Alice's public text.
        String alice = "00000000-0000-0000-0000-000000000001";
        String bob = "00000000-0000-0000-0000-000000000002";
        String ghost = "00000000-0000-0000-0000-000000000003";
        DeathEntry aliceDeath = death(alice, "Alice", 0, 0, 0,
                "Alice was slain by GHOST and bobby and BOB near " + ghost,
                "gHoSt", "BOBBY did it", "Bye aLiCe");
        PlayerDeaths ghostPlayer = new PlayerDeaths(ghost, "Ghost", true,
                List.of(death(ghost, "Bobby", 0, 0, 0, "Bobby fell", "", "", "")));
        List<PlayerDeaths> all = List.of(
                new PlayerDeaths(alice, "Alice", false, List.of(aliceDeath)),
                new PlayerDeaths(bob, "Bob", false, List.of()),
                ghostPlayer);
        List<PlayerDeaths> exported = List.of(all.get(0), all.get(1));

        List<PlayerDeaths> redacted = Anonymizer.apply(exported, all);
        assertEquals(2, redacted.size());
        assertEquals("Player A", redacted.get(0).name());
        assertEquals("Player B", redacted.get(1).name());
        DeathEntry clean = redacted.get(0).deaths().get(0);
        assertNoLeaks(clean.deathMessage(), "Ghost", "Bobby", "Bob", ghost);
        assertNoLeaks(clean.killerName(), "Ghost");
        assertNoLeaks(clean.excuse(), "Bobby");
        assertEquals("Bye Player A", clean.lastWords(), "review M5: case-insensitive scrubbing");

        for (String rendered : List.of(
                ExportRenderers.toCsv(exported, true, all),
                ExportRenderers.toJson(exported, T, true, all),
                ExportRenderers.toHtml(exported, T, ZoneOffset.UTC, true, all))) {
            assertNoLeaks(rendered, "Ghost", "Bobby", "Bob", ghost);
            assertTrue(rendered.contains("Player A"));
        }
        assertEquals("Player C", Anonymizer.apply(all).get(2).name(),
                "the full snapshot keeps deterministic pseudonym numbering");
    }

    @Test
    void singlePlayerExportStillUsesTheFullSnapshotDictionary() {
        // Review S4: /dc export json Alice --anonymize only knows Alice, but the
        // killer's identity is in the full snapshot and must still be scrubbed.
        String alice = "00000000-0000-0000-0000-000000000011";
        String carol = "00000000-0000-0000-0000-000000000012";
        DeathEntry aliceDeath = death(alice, "Alice", 0, 0, 0,
                "Alice was slain by Carol", "Carol", "gg carol", "bye " + carol);
        List<PlayerDeaths> all = List.of(
                new PlayerDeaths(alice, "Alice", false, List.of(aliceDeath)),
                new PlayerDeaths(carol, "Carol", true, List.of()));
        List<PlayerDeaths> onlyAlice = List.of(all.get(0));

        DeathEntry clean = Anonymizer.apply(onlyAlice, all).get(0).deaths().get(0);
        assertNoLeaks(clean.deathMessage(), "Alice", "Carol");
        assertEquals("Player B", clean.killerName());
        assertEquals("gg Player B", clean.excuse());
        assertNoLeaks(clean.lastWords(), carol);

        for (String rendered : List.of(
                ExportRenderers.toCsv(onlyAlice, true, all),
                ExportRenderers.toJson(onlyAlice, T, true, all),
                ExportRenderers.toHtml(onlyAlice, T, ZoneOffset.UTC, true, all))) {
            assertNoLeaks(rendered, "Carol", carol);
            assertTrue(rendered.contains("Player A"));
        }
    }

    @Test
    void scrubbingIsCaseInsensitiveForNamesAndUuids() {
        // Review M5: lower-cased spellings of a player name must not survive.
        String alice = "00000000-0000-0000-0000-000000000021";
        String steve = "00000000-0000-0000-0000-000000000022";
        DeathEntry d = death(alice, "Alice", 0, 0, 0,
                "alice was slain by steve", "sTeVe", "gg alice", "bye STEVE");
        List<PlayerDeaths> players = List.of(
                new PlayerDeaths(alice, "Alice", false, List.of(d)),
                new PlayerDeaths(steve, "Steve", false, List.of()));

        DeathEntry clean = Anonymizer.apply(players).get(0).deaths().get(0);
        assertEquals("Player A was slain by Player B", clean.deathMessage());
        assertEquals("Player B", clean.killerName());
        assertEquals("gg Player A", clean.excuse());
        assertEquals("bye Player B", clean.lastWords());
    }
}
