package com.pengin0503.deathcensus;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-tree wiring check for the 1.4.0 PR-6 nemesis surface (§4). */
class NemesisWiringTest {
    private static String source(String file) throws IOException {
        String dir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(dir, "deathcensus.mainSourceDir system property is not set");
        return Files.readString(Path.of(dir, "com", "pengin0503", "deathcensus", file));
    }

    private static JsonObject loadLang(String lang) throws IOException {
        String path = "/assets/deathcensus/lang/" + lang + ".json";
        try (InputStream in = NemesisWiringTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing lang resource " + path);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    @Test
    void configDefaultsMatchTheRecommendedValues() {
        assertTrue(Config.nemesisEnabled());
        assertEquals(3, Config.nemesisMinMobCount());
        assertEquals(2, Config.nemesisMinNamedCount());
        assertFalse(Config.nemesisInDigest());
    }

    @Test
    void configDeclaresAllFourKeysWithTheSpecifiedClamps() throws IOException {
        String config = source("Config.java");
        assertTrue(config.contains("define(\"nemesisEnabled\", true)"), config);
        assertTrue(config.contains("defineInRange(\"nemesisMinMobCount\", 3, 1, 100)"), config);
        assertTrue(config.contains("defineInRange(\"nemesisMinNamedCount\", 2, 1, 100)"), config);
        assertTrue(config.contains("define(\"nemesisInDigest\", false)"), config);
    }

    @Test
    void commandTreeExposesEveryNemesisArgumentShape() throws IOException {
        // Source check rather than Brigadier registration: on the 26.x trees
        // Commands.<clinit> needs a bootstrapped registry, which a direct javac
        // harness intentionally does not start.
        String commands = source("CensusCommands.java");
        assertTrue(commands.contains("literal(\"nemesis\")"), commands);
        assertTrue(commands.contains("argument(\"player\", StringArgumentType.word())"), commands);
        assertTrue(commands.contains("argument(\"page\", IntegerArgumentType.integer(1))"), commands);
        assertTrue(commands.contains("literal(mode.token())"), commands);
        assertEquals("mob", NemesisAnalysis.Mode.MOB_SPECIES.token());
        assertEquals("named", NemesisAnalysis.Mode.NAMED_RIVAL.token());
        assertEquals("pvp", NemesisAnalysis.Mode.PVP_RIVAL.token());
        assertTrue(commands.contains("command.deathcensus.nemesis.header"), commands);
        assertTrue(commands.contains("command.deathcensus.nemesis.empty"), commands);
        assertTrue(commands.contains("command.deathcensus.nemesis.help"), commands);
        assertTrue(commands.contains("NemesisAnalysis.Mode.MOB_SPECIES, 1"), commands);
    }

    @Test
    void exportAndUiUseTheSharedProjection() throws IOException {
        String export = source("CensusExport.java");
        assertTrue(export.contains("toJsonWithChronicle(players, now"), export);
        assertTrue(export.contains("toCsvWithChronicle(players, anonymize"), export);
        assertTrue(export.contains("toHtmlWithChronicle(players, System.currentTimeMillis()"), export);
        assertTrue(export.contains("command.deathcensus.export.nemesis"), export);
        assertTrue(com.pengin0503.deathcensus.ui.UiSession.RankingSelection.TYPES.contains("nemesis"));
    }

    @Test
    void allNemesisKeysExistInBothBundles() throws IOException {
        JsonObject en = loadLang("en_us");
        JsonObject ja = loadLang("ja_jp");
        List<String> keys = List.of(
                "command.deathcensus.nemesis.help",
                "command.deathcensus.nemesis.header",
                "command.deathcensus.nemesis.table.mob",
                "command.deathcensus.nemesis.table.named",
                "command.deathcensus.nemesis.table.pvp",
                "command.deathcensus.nemesis.row",
                "command.deathcensus.nemesis.empty",
                "command.deathcensus.nemesis.anonymous",
                "command.deathcensus.ui.ranking.nemesis",
                "command.deathcensus.export.nemesis");
        for (String key : keys) {
            assertTrue(en.has(key), "missing en_us entry for " + key);
            assertTrue(ja.has(key), "missing ja_jp entry for " + key);
            assertFalse(en.get(key).getAsString().isBlank(), "blank en_us entry for " + key);
            assertFalse(ja.get(key).getAsString().isBlank(), "blank ja_jp entry for " + key);
        }
        assertEquals(keys.size(), 10);
        assertTrue(en.get("command.deathcensus.ui.chat.ranking_hint").getAsString().contains("nemesis"));
        assertTrue(ja.get("command.deathcensus.ui.chat.ranking_hint").getAsString().contains("nemesis"));
    }

    @Test
    void commonModeTokensAreStableAndProjectionIsPrivacyAware() {
        assertEquals(NemesisAnalysis.Mode.MOB_SPECIES, NemesisAnalysis.Mode.fromToken("mob"));
        assertEquals(NemesisAnalysis.Mode.NAMED_RIVAL, NemesisAnalysis.Mode.fromToken("named"));
        assertEquals(NemesisAnalysis.Mode.PVP_RIVAL, NemesisAnalysis.Mode.fromToken("pvp"));
        assertNull(NemesisAnalysis.Mode.fromToken("unknown"));

        DeathEntry death = new DeathEntry("victim", "Victim", 1_000L, 1L, 10, 10, "d", 0, 0, 0,
                "player", "minecraft:player", "slain", "minecraft:player", "Steve",
                "survival", 0, "", "", 0);
        List<PlayerDeaths> snapshot = List.of(
                new PlayerDeaths("victim", "Victim", false, false, false, false, List.of(death)),
                new PlayerDeaths("steve", "Steve", true, false, false, false, List.of()));
        List<NemesisRow> rows = NemesisAnalysis.rank(List.of(snapshot.get(0)),
                NemesisAnalysis.Mode.PVP_RIVAL, 1, snapshot,
                WitnessRedactor.namesById(snapshot), WitnessRedactor.privacyById(snapshot), false);
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).anonymous(), rows.toString());
    }
}
