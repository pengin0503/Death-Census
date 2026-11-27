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
    void witnessesFollowTheSamePseudonymMappingIncludingNonPlayerIdentities() {
        String alpha = "00000000-0000-0000-0000-00000000000a";
        String beta = "00000000-0000-0000-0000-00000000000b";
        String outsider = "99999999-9999-9999-9999-999999999999";
        DeathEntry d = new DeathEntry(alpha, "Zed", T, 1L, 100, 100, "minecraft:overworld",
                1.0, 2.0, 3.0, "fall", "minecraft:fall", "Zed fell with Beta",
                "", "", "survival", 1, "", "", 0,
                List.of(new WitnessRef(beta, "Beta"), new WitnessRef(outsider, "Dave")));
        List<PlayerDeaths> original = List.of(new PlayerDeaths(alpha, "Zed", false, List.of(d)));

        List<PlayerDeaths> redacted = Anonymizer.apply(original);
        List<WitnessRef> witnesses = redacted.get(0).deaths().get(0).witnesses();
        assertEquals(2, witnesses.size());
        assertEquals("anon-2", witnesses.get(0).uuid()); // witness-only identities follow the players
        assertEquals("Player B", witnesses.get(0).nameAtDeath());
        assertEquals("anon-3", witnesses.get(1).uuid());
        assertEquals("Player C", witnesses.get(1).nameAtDeath());
        assertNoLeaks(ExportRenderers.toJson(original, T, true), "Zed", "Beta", "Dave", beta, outsider);
        assertEquals("Beta", original.get(0).deaths().get(0).witnesses().get(0).nameAtDeath());
    }

    @Test
    void contextKeepsRegistryIdsButDropsTheLocationDerivedBiome() {
        String uuid = "00000000-0000-0000-0000-00000000000a";
        DeathContext context = new DeathContext(uuid, List.of("minecraft:helmet"),
                List.of("minecraft:speed:1"), "minecraft:plains", "clear");
        DeathEntry d = new DeathEntry(uuid, "Zed", T, 1L, 100, 100, "minecraft:overworld",
                1.0, 2.0, 3.0, "fall", "minecraft:fall", "Zed fell",
                "", "", "survival", 1, "", "", 0, List.of(), context);
        List<PlayerDeaths> original = List.of(new PlayerDeaths(uuid, "Zed", false, List.of(d)));

        DeathContext anon = Anonymizer.apply(original).get(0).deaths().get(0).context();
        assertEquals("anon-1", anon.mainHand(), "player uuid used as an id is scrubbed");
        assertEquals(List.of("minecraft:helmet"), anon.armor());
        assertEquals(List.of("minecraft:speed:1"), anon.effects());
        assertEquals("", anon.biome());
        assertEquals("clear", anon.weather());
        assertEquals("minecraft:plains", original.get(0).deaths().get(0).context().biome());
        assertNoLeaks(ExportRenderers.toJson(original, T, true), "minecraft:plains");
    }
}
