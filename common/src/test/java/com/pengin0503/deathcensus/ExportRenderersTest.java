package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportRenderersTest {

    private static final long T1 = Instant.parse("2026-11-24T10:15:00Z").toEpochMilli();
    private static final long T2 = Instant.parse("2026-11-24T12:30:00Z").toEpochMilli();

    @Test
    void csvHeaderSortingFormattingAndQuoting() {
        DeathEntry later = new DeathEntry(
                "u1", "Alice, Jr.", T2, 5L, 2000, 1200,
                "minecraft:the_nether", 12.34, 64.0, -30.56,
                "lava", "minecraft:lava", "Alice \"tried\" to swim, badly",
                "", "", "survival", 12, "It was the lava's fault", "See you on the other side", 3
        );
        DeathEntry earlier = new DeathEntry(
                "u2", "Bob", T1, 2L, 500, 500,
                "minecraft:overworld", 1.0, 70.5, 2.0,
                "fall", "minecraft:fall", "Bob fell\nfrom high",
                "", "", "survival", 3, "", "", 0
        );
        List<PlayerDeaths> players = List.of(
                new PlayerDeaths("u1", "Alice, Jr.", false, List.of(later)),
                new PlayerDeaths("u2", "Bob", false, List.of(earlier))
        );

        String csv = ExportRenderers.toCsv(players);
        String expectedHeader = "player_uuid,player_name,epoch_millis,datetime_iso,game_day,play_ticks,"
                + "survival_ticks,dimension,x,y,z,cause_category,cause_id,killer_type,killer_name,"
                + "game_mode,experience_level,death_message,excuse,last_words,f_count\n";
        assertTrue(csv.startsWith(expectedHeader));
        assertTrue(csv.endsWith("\n"));

        // Earlier death appears before later death
        int bobIdx = csv.indexOf("u2,Bob,");
        int aliceIdx = csv.indexOf("u1,\"Alice, Jr.\",");
        assertTrue(bobIdx > 0 && aliceIdx > bobIdx);

        assertTrue(csv.contains("\"Bob fell\nfrom high\""));
        assertTrue(csv.contains("\"Alice \"\"tried\"\" to swim, badly\""));
        assertTrue(csv.contains(",12.3,64.0,-30.6,"));
        assertTrue(csv.contains(",2026-11-24T10:15:00Z,"));
        assertTrue(csv.contains(",\"Alice \"\"tried\"\" to swim, badly\",It was the lava's fault,See you on the other side,3\n"));
    }

    @Test
    void jsonContainsExpectedFieldsAndEscaping() {
        DeathEntry d = new DeathEntry(
                "u1", "A\"B\\C", T1, 3L, 1000, 600,
                "minecraft:overworld", 10.5, 64.0, -5.25,
                "mob", "minecraft:mob", "Line1\nLine2\t\"quote\"",
                "minecraft:zombie", "Bob\\Zombie", "survival", 7,
                "not \"my\" fault", "goodbye\nworld", 2
        );
        List<PlayerDeaths> players = List.of(new PlayerDeaths("u1", "A\"B\\C", true, List.of(d)));

        String json = ExportRenderers.toJson(players, T2);
        assertTrue(json.contains("\"generatedAt\": " + T2));
        assertTrue(json.contains("\"totalDeaths\": 1"));
        assertTrue(json.contains("\"optOutPublic\": true"));
        assertTrue(json.contains("\"name\": \"A\\\"B\\\\C\""));
        assertTrue(json.contains("\"deathMessage\": \"Line1\\nLine2\\t\\\"quote\\\"\""));
        assertTrue(json.contains("\"killerName\": \"Bob\\\\Zombie\""));
        assertTrue(json.contains("\"experienceLevel\": 7"));
        assertTrue(json.contains("\"excuse\": \"not \\\"my\\\" fault\""));
        assertTrue(json.contains("\"lastWords\": \"goodbye\\nworld\""));
        assertTrue(json.contains("\"fCount\": 2"));
    }

    @Test
    void htmlEscapesUserStringsAndContainsNoScript() {
        DeathEntry d = new DeathEntry(
                "u1", "<script>alert(1)</script>", T1, 4L, 2400, 1200,
                "minecraft:overworld", 1.2, 64.0, 3.4,
                "fire", "minecraft:in_fire", "Burned by <b>fire</b> & \"sparks\"",
                "", "", "survival", 1,
                "It was <b>not</b> my fault", "goodbye <script>alert(2)</script>", 4
        );
        List<PlayerDeaths> players = List.of(
                new PlayerDeaths("u1", "<script>alert(1)</script>", false, List.of(d))
        );

        String html = ExportRenderers.toHtml(players, T2, ZoneOffset.UTC);
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("<h1>Death Census</h1>"));
        assertTrue(html.contains("<style>"));
        assertFalse(html.toLowerCase().contains("<script"));
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertTrue(html.contains("Burned by &lt;b&gt;fire&lt;/b&gt; &amp; &quot;sparks&quot;"));
        assertTrue(html.contains("2026-11-24 10:15"));
        assertTrue(html.contains("1m 0s"));
        assertTrue(html.contains("Excuse: &quot;It was &lt;b&gt;not&lt;/b&gt; my fault&quot;"));
        assertTrue(html.contains("Last words: &quot;goodbye &lt;script&gt;alert(2)&lt;/script&gt;&quot;"));
        assertTrue(html.contains("F x4"));
    }

    @Test
    void anonymizeOverloadsRedactWhileNormalOverloadsStayIdentical() {
        String uuid = "00000000-0000-0000-0000-0000000000ff";
        String killerUuid = "00000000-0000-0000-0000-000000000100";
        DeathEntry d = new DeathEntry(
                uuid, "A\"B", T1, 4L, 2400, 1200,
                "minecraft:overworld", 130.0, -130.0, 64.0,
                "player", "minecraft:player", "A\"B hit Bob, then ran",
                "minecraft:player", "Bob", "survival", 9,
                "Bob did it", "Bye Bob", 1
        );
        List<PlayerDeaths> players = List.of(
                new PlayerDeaths(uuid, "A\"B", false, List.of(d)),
                new PlayerDeaths(killerUuid, "Bob", false, List.of()));

        assertEquals(ExportRenderers.toCsv(players), ExportRenderers.toCsv(players, false));
        assertEquals(ExportRenderers.toJson(players, T2), ExportRenderers.toJson(players, T2, false));
        assertEquals(ExportRenderers.toHtml(players, T2, ZoneOffset.UTC),
                ExportRenderers.toHtml(players, T2, ZoneOffset.UTC, false));

        String csv = ExportRenderers.toCsv(players, true);
        String json = ExportRenderers.toJson(players, T2, true);
        String html = ExportRenderers.toHtml(players, T2, ZoneOffset.UTC, true);
        // Pseudonyms are identical in all three formats; redaction does not
        // change CSV quoting, JSON numeric coordinates or the no-script HTML rule.
        assertTrue(csv.contains("\"Player A hit Player B, then ran\""));
        assertTrue(csv.contains(",128.0,-128.0,64.0,"));
        assertTrue(json.contains("\"anonymized\": true"));
        assertTrue(json.contains("\"playerName\": \"Player A\""));
        assertTrue(json.contains("\"x\": 128.0"));
        assertTrue(html.contains("Player A"));
        assertFalse(html.toLowerCase().contains("<script"));
        for (String rendered : List.of(csv, json, html)) {
            assertFalse(rendered.contains(uuid), rendered);
            assertFalse(rendered.contains(killerUuid), rendered);
            assertFalse(rendered.contains("A\\\"B"), rendered);
            assertFalse(rendered.contains("Bob"), rendered);
        }
    }

    @Test
    void granularPrivacyRedactsRowsButKeepsAnonymousTotals() {
        DeathEntry d = new DeathEntry(
                "u1", "Alex", T1, 4L, 2400, 1200,
                "minecraft:overworld", 130.0, -130.0, 64.0,
                "player", "minecraft:player", "Alex was slain by Steve",
                "minecraft:player", "Steve", "survival", 9, "", "", 1
        );
        List<PlayerDeaths> players = List.of(
                new PlayerDeaths("u1", "Alex", false, true, true, true, List.of(d))
        );

        String csv = ExportRenderers.toCsv(players);
        String json = ExportRenderers.toJson(players, T2);
        String html = ExportRenderers.toHtml(players, T2, ZoneOffset.UTC);
        for (String rendered : List.of(csv, json, html)) {
            assertTrue(rendered.contains("~"), rendered);
            assertFalse(rendered.contains("Steve"), rendered);
            assertTrue(rendered.contains("player"), rendered);
        }
        assertTrue(csv.contains("~,~,~,"));
        assertTrue(json.contains("\"x\": \"~\""));
        assertTrue(json.contains("\"y\": \"~\""));
        assertTrue(json.contains("\"z\": \"~\""));
        assertTrue(json.contains("\"hideCoordinates\": true"));
        assertTrue(json.contains("\"hidePvpOpponent\": true"));
        assertTrue(json.contains("\"discordOptOut\": true"));
        assertTrue(json.contains("\"totalDeaths\": 1"));
        assertTrue(html.contains("~</td>"));
        assertFalse(DeathRedactor.isCoordinateHidden(d), "the source snapshot is not redacted");
    }
}
