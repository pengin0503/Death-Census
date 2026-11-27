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
                + "game_mode,experience_level,death_message,excuse,last_words,f_count,witnesses,"
                + "context_main_hand,context_armor,context_effects,context_biome,context_weather\n";
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
        assertTrue(csv.contains(",\"Alice \"\"tried\"\" to swim, badly\",It was the lava's fault,See you on the other side,3,,,,,,\n"));
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

    @Test
    void witnessesAppearInEveryExportAndFollowPrivacyComposition() {
        DeathEntry d = new DeathEntry("u1", "Alice", T1, 3L, 1000, 600,
                "minecraft:overworld", 10.5, 64.0, -5.25,
                "fall", "minecraft:fall", "Alice fell", "", "", "survival", 7,
                "", "", 2,
                List.of(new WitnessRef("u2", "Bob at death"), new WitnessRef("u3", "Carol at death")));
        List<PlayerDeaths> players = List.of(
                new PlayerDeaths("u1", "Alice", false, List.of(d)),
                new PlayerDeaths("u2", "Bob renamed", false, List.of()),
                new PlayerDeaths("u3", "Carol", true, false, false, false, List.of()));

        String csv = ExportRenderers.toCsv(players);
        String json = ExportRenderers.toJson(players, T2);
        String html = ExportRenderers.toHtml(players, T2, ZoneOffset.UTC);
        String backup = ExportRenderers.toBackupJson(players, T2);

        assertTrue(csv.contains("\"Bob renamed, Anonymous\""), csv);
        assertTrue(json.contains("\"witnesses\": [\"Bob renamed\", \"Anonymous\"]"), json);
        assertTrue(html.contains("<th>Witnesses</th>"), html);
        assertTrue(html.contains("Bob renamed, Anonymous"), html);
        assertEquals("Carol at death", d.witnesses().get(1).nameAtDeath(), "source record untouched");
        // PR-1 backup seam stays object-shaped for restore compatibility.
        assertTrue(backup.contains("\"witnesses\": ["), backup);
        assertTrue(backup.contains("\"nameAtDeath\": \"Bob at death\""), backup);
    }

    @Test
    void anonymizedExportsScrubWitnessNamesAndUuids() {
        DeathEntry d = new DeathEntry("u1", "Alice", T1, 3L, 1000, 600,
                "minecraft:overworld", 10.5, 64.0, -5.25,
                "fall", "minecraft:fall", "Alice fell", "", "", "survival", 7,
                "", "", 2,
                List.of(new WitnessRef("u2", "Bob"), new WitnessRef("outsider-uuid", "Dave")));
        List<PlayerDeaths> players = List.of(
                new PlayerDeaths("u1", "Alice", false, List.of(d)),
                new PlayerDeaths("u2", "Bob", false, List.of()));

        String csv = ExportRenderers.toCsv(players, true);
        String json = ExportRenderers.toJson(players, T2, true);
        String html = ExportRenderers.toHtml(players, T2, ZoneOffset.UTC, true);
        for (String rendered : List.of(csv, json, html)) {
            assertTrue(rendered.contains("Player B"), rendered);
            assertFalse(rendered.contains("Dave"), rendered);
            assertFalse(rendered.contains("outsider-uuid"), rendered);
        }
        assertEquals("Player B", Anonymizer.apply(players).get(0).deaths().get(0).witnesses().get(0).nameAtDeath());
    }

    @Test
    void contextFieldsAppearInEveryPublicExportAndFollowRedaction() {
        DeathContext context = new DeathContext("minecraft:sword", List.of("minecraft:helmet"),
                List.of("minecraft:speed:1"), "minecraft:plains", "thunder");
        DeathEntry d = new DeathEntry("u1", "Alice", T1, 3L, 1000, 600,
                "minecraft:overworld", 10.5, 64.0, -5.25,
                "fall", "minecraft:fall", "Alice fell", "", "", "survival", 7,
                "", "", 2, List.of(), context);
        List<PlayerDeaths> players = List.of(new PlayerDeaths("u1", "Alice", false, true, false, false,
                List.of(d)));

        String csv = ExportRenderers.toCsv(players);
        String json = ExportRenderers.toJson(players, T2);
        String html = ExportRenderers.toHtml(players, T2, ZoneOffset.UTC);
        for (String rendered : List.of(csv, json, html)) {
            assertTrue(rendered.contains("minecraft:sword"), rendered);
            assertTrue(rendered.contains("minecraft:helmet"), rendered);
            assertTrue(rendered.contains("minecraft:speed:1"), rendered);
            assertTrue(rendered.contains("thunder"), rendered);
            assertFalse(rendered.contains("minecraft:plains"), rendered + " (hideCoordinates redacts biome)");
        }
        assertTrue(csv.contains("context_main_hand,context_armor,context_effects,context_biome,context_weather"));
        assertTrue(json.contains("\"mainHand\": \"minecraft:sword\""));
        assertTrue(json.contains("\"biome\": \"~\""));
        assertTrue(html.contains("<th>Context</th>"));
        assertEquals("minecraft:plains", d.context().biome(), "source record untouched");

        // Backup JSON keeps the stored biome for restore even when public views hide it.
        String backup = ExportRenderers.toBackupJson(players, T2);
        assertTrue(backup.contains("\"biome\": \"minecraft:plains\""), backup);
    }

    @Test
    void exportSwitchCanOmitContextWithoutChangingOtherFields() {
        DeathEntry d = new DeathEntry("u1", "Alice", T1, 3L, 1000, 600,
                "d", 10.5, 64.0, -5.25, "fall", "minecraft:fall", "Alice fell",
                "", "", "survival", 7, "", "", 2, List.of(),
                new DeathContext("minecraft:sword", List.of(), List.of(), "minecraft:plains", "clear"));
        List<PlayerDeaths> players = List.of(new PlayerDeaths("u1", "Alice", false, List.of(d)));

        String csv = ExportRenderers.toCsv(players, false, false);
        String json = ExportRenderers.toJson(players, T2, false, false);
        String html = ExportRenderers.toHtml(players, T2, ZoneOffset.UTC, false, false);
        for (String rendered : List.of(csv, json, html)) {
            assertFalse(rendered.contains("minecraft:sword"), rendered);
            assertFalse(rendered.contains("context_main_hand"), rendered);
        }
        assertTrue(csv.contains("Alice fell"));
        assertTrue(json.contains("\"causeId\": \"minecraft:fall\""));
        assertTrue(html.contains("<th>Witnesses</th>"));
    }

    @Test
    void publicExportsScrubHiddenIdentitiesFromTheFullSnapshot() {
        // Review S3: Ghost is opted out and filtered out of the export, but is
        // named in Bob's public record. The dictionary still comes from the
        // full snapshot, so all three formats must be clean.
        String ghostUuid = "00000000-0000-0000-0000-000000000001";
        DeathEntry ghostDeath = new DeathEntry(ghostUuid, "Ghost", T1, 1L, 100, 100,
                "minecraft:overworld", 1, 2, 3, "fall", "minecraft:fall", "Ghost fell",
                "", "", "survival", 1, "", "", 0);
        DeathEntry bobDeath = new DeathEntry("u-bob", "Bob", T2, 2L, 100, 100,
                "minecraft:overworld", 1, 2, 3, "player", "minecraft:player",
                "BOB was slain by GHOST and " + ghostUuid,
                "minecraft:player", "gHoSt", "survival", 1, "GHOST did it", "bye Ghost", 0);
        PlayerDeaths ghost = new PlayerDeaths(ghostUuid, "Ghost", true, List.of(ghostDeath));
        PlayerDeaths bob = new PlayerDeaths("u-bob", "Bob", false, List.of(bobDeath));
        List<PlayerDeaths> full = List.of(ghost, bob);
        List<PlayerDeaths> exported = List.of(bob);

        for (String rendered : List.of(
                ExportRenderers.toCsv(exported, false, true, full),
                ExportRenderers.toJson(exported, T2, false, true, full),
                ExportRenderers.toHtml(exported, T2, ZoneOffset.UTC, false, true, "Context", full))) {
            assertFalse(rendered.toLowerCase().contains("ghost"), rendered);
            assertFalse(rendered.contains(ghostUuid), rendered);
        }
        // S2 is explicitly out of scope: backups keep their deliberate redaction
        // path, and an opted-out player's own name is still in the backup.
        assertTrue(ExportRenderers.toBackupJson(full, T2).contains("\"name\": \"Ghost\""));
    }
}
