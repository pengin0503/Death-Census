package com.pengin0503.deathchronicle;

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
                "", "", "survival", 12
        );
        DeathEntry earlier = new DeathEntry(
                "u2", "Bob", T1, 2L, 500, 500,
                "minecraft:overworld", 1.0, 70.5, 2.0,
                "fall", "minecraft:fall", "Bob fell\nfrom high",
                "", "", "survival", 3
        );
        List<PlayerDeaths> players = List.of(
                new PlayerDeaths("u1", "Alice, Jr.", false, List.of(later)),
                new PlayerDeaths("u2", "Bob", false, List.of(earlier))
        );

        String csv = ExportRenderers.toCsv(players);
        String expectedHeader = "player_uuid,player_name,epoch_millis,datetime_iso,game_day,play_ticks,"
                + "survival_ticks,dimension,x,y,z,cause_category,cause_id,killer_type,killer_name,"
                + "game_mode,experience_level,death_message\n";
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
    }

    @Test
    void jsonContainsExpectedFieldsAndEscaping() {
        DeathEntry d = new DeathEntry(
                "u1", "A\"B\\C", T1, 3L, 1000, 600,
                "minecraft:overworld", 10.5, 64.0, -5.25,
                "mob", "minecraft:mob", "Line1\nLine2\t\"quote\"",
                "minecraft:zombie", "Bob\\Zombie", "survival", 7
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
    }

    @Test
    void htmlEscapesUserStringsAndContainsNoScript() {
        DeathEntry d = new DeathEntry(
                "u1", "<script>alert(1)</script>", T1, 4L, 2400, 1200,
                "minecraft:overworld", 1.2, 64.0, 3.4,
                "fire", "minecraft:in_fire", "Burned by <b>fire</b> & \"sparks\"",
                "", "", "survival", 1
        );
        List<PlayerDeaths> players = List.of(
                new PlayerDeaths("u1", "<script>alert(1)</script>", false, List.of(d))
        );

        String html = ExportRenderers.toHtml(players, T2, ZoneOffset.UTC);
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("<h1>Death Chronicle</h1>"));
        assertTrue(html.contains("<style>"));
        assertFalse(html.toLowerCase().contains("<script"));
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertTrue(html.contains("Burned by &lt;b&gt;fire&lt;/b&gt; &amp; &quot;sparks&quot;"));
        assertTrue(html.contains("2026-11-24 10:15"));
        assertTrue(html.contains("1m 0s"));
    }
}
