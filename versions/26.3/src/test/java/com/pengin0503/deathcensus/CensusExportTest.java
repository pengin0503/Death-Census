package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Filename rule for anonymous exports (DESIGN 1.3.0 §6). */
class CensusExportTest {
    @Test
    void anonymizedFileNamesHaveSuffixAndNeverCarryAPlayerName() {
        for (String format : List.of("html", "json", "csv")) {
            String normal = CensusExport.exportFileName(format, "Alice", false, ZoneOffset.UTC);
            assertTrue(normal.startsWith("deathcensus-"));
            assertTrue(normal.contains("-Alice." + format));
            assertFalse(normal.contains("-anonymized"));

            String anonymized = CensusExport.exportFileName(format, "Alice", true, ZoneOffset.UTC);
            assertTrue(anonymized.matches("deathcensus-\\d{8}-\\d{6}-anonymized\\." + format), anonymized);
            assertFalse(anonymized.contains("Alice"), anonymized);

            String noPlayer = CensusExport.exportFileName(format, null, true, ZoneOffset.UTC);
            assertTrue(noPlayer.matches("deathcensus-\\d{8}-\\d{6}-anonymized\\." + format), noPlayer);
        }
    }

    @Test
    void normalFileNameStillSanitizesPlayerNames() {
        assertEquals("Alice_Smith", CensusExport.sanitizeFilePart("Alice Smith"));
        String name = CensusExport.exportFileName("json", "Alice Smith", false, ZoneOffset.UTC);
        assertTrue(name.contains("-Alice_Smith.json"), name);
        assertFalse(name.contains(" "), name);
    }

    private static final String U1 = "00000000-0000-0000-0000-000000000001";
    private static final String U2 = "00000000-0000-0000-0000-000000000002";

    @Test
    void oldBackupWithoutGranularFieldsLoadsWithDefaultsAndRoundTrips() {
        String oldJson = """
                {"players":[{"uuid":"%s","name":"Alice","optOutPublic":true,
                "deaths":[{"epochMillis":1000,"gameDay":1,"causeCategory":"fall",
                "causeId":"minecraft:fall","deathMessage":"Alice fell","x":1.0,"y":2.0,"z":3.0}]}]}
                """.formatted(U1);

        List<PlayerDeaths> parsed = CensusExport.parseBackupJson(oldJson);
        assertNotNull(parsed);
        assertEquals(1, parsed.size());
        PlayerDeaths player = parsed.get(0);
        assertTrue(player.optOutPublic());
        assertFalse(player.hideCoordinates());
        assertFalse(player.hidePvpOpponent());
        assertFalse(player.discordOptOut());
        assertEquals(1, player.deaths().size());
        assertEquals(1.0, player.deaths().get(0).x());

        String rewritten = ExportRenderers.toJson(parsed, 1000L);
        assertTrue(rewritten.contains("\"hideCoordinates\": false"));
        assertTrue(rewritten.contains("\"hidePvpOpponent\": false"));
        assertTrue(rewritten.contains("\"discordOptOut\": false"));
        List<PlayerDeaths> again = CensusExport.parseBackupJson(rewritten);
        assertNotNull(again);
        assertEquals(1, again.size());
        assertFalse(again.get(0).hideCoordinates());
        assertFalse(again.get(0).hidePvpOpponent());
        assertFalse(again.get(0).discordOptOut());
        assertEquals(1, again.get(0).deaths().size());
    }

    @Test
    void backupJsonRoundTripsAllPrivacyFlagsIncludingRedactionMarkers() {
        DeathEntry death = new DeathEntry(U1, "Alice", 1000L, 1L, 300, 200,
                "minecraft:overworld", 12.5, 64.0, -7.25, "player", "minecraft:player",
                "Alice was slain by Steve", "minecraft:player", "Steve",
                "survival", 4, "why", "gg", 2);
        PlayerDeaths player = new PlayerDeaths(U1, "Alice", true, true, true, true, List.of(death));

        String json = ExportRenderers.toJson(List.of(player), 1000L);
        assertTrue(json.contains("\"optOutPublic\": true"));
        assertTrue(json.contains("\"hideCoordinates\": true"));
        assertTrue(json.contains("\"hidePvpOpponent\": true"));
        assertTrue(json.contains("\"discordOptOut\": true"));
        assertTrue(json.contains("\"x\": \"~\""));
        assertFalse(json.contains("Steve"), json);

        List<PlayerDeaths> parsed = CensusExport.parseBackupJson(json);
        assertNotNull(parsed);
        assertEquals(1, parsed.size());
        PlayerDeaths restored = parsed.get(0);
        assertTrue(restored.optOutPublic());
        assertTrue(restored.hideCoordinates());
        assertTrue(restored.hidePvpOpponent());
        assertTrue(restored.discordOptOut());
        assertEquals(1, restored.deaths().size());
        assertTrue(Double.isNaN(restored.deaths().get(0).x()));
        assertEquals("", restored.deaths().get(0).killerName());
        assertFalse(restored.deaths().get(0).deathMessage().contains("Steve"));
        assertTrue(ExportRenderers.toJson(parsed, 1000L).contains("\"x\": \"~\""));
    }

    @Test
    void backupJsonSkipsCorruptEntriesWithoutDroppingValidSiblings() {
        String json = """
                {"players":[
                 {"uuid":"%s","name":"Good","optOutPublic":false,"hidePvpOpponent":true,
                  "deaths":[{"epochMillis":1000,"causeCategory":"fall","x":1.0},
                            {"epochMillis":"oops","causeCategory":"fall"},
                            {"causeCategory":"fall"}]},
                 {"uuid":42,"name":"Bad"},
                 {"uuid":"%s","name":"BadFlag","hideCoordinates":"yes",
                  "deaths":[{"epochMillis":2000,"causeCategory":"fall"}]}
                ]}
                """.formatted(U1, U2);

        List<PlayerDeaths> parsed = CensusExport.parseBackupJson(json);
        assertNotNull(parsed);
        assertEquals(2, parsed.size(), "only the corrupt player entry is skipped");
        assertEquals("Good", parsed.get(0).name());
        assertTrue(parsed.get(0).hidePvpOpponent());
        assertEquals(1, parsed.get(0).deaths().size());
        assertEquals("BadFlag", parsed.get(1).name());
        assertFalse(parsed.get(1).hideCoordinates(), "wrong-typed flag defaults to false");
        assertEquals(1, parsed.get(1).deaths().size());
        assertEquals(2000L, parsed.get(1).deaths().get(0).epochMillis());
    }
}
