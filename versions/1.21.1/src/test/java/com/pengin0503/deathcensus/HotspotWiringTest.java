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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-tree wiring check for the 1.4.0 PR-7 hotspot surface (§5). */
class HotspotWiringTest {
    private static String source(String file) throws IOException {
        String dir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(dir, "deathcensus.mainSourceDir system property is not set");
        return Files.readString(Path.of(dir, "com", "pengin0503", "deathcensus", file));
    }

    private static JsonObject loadLang(String lang) throws IOException {
        String path = "/assets/deathcensus/lang/" + lang + ".json";
        try (InputStream in = HotspotWiringTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing lang resource " + path);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    @Test
    void configDefaultsMatchTheRecommendedValues() {
        assertTrue(Config.hotspotsEnabled());
        assertEquals(3, Config.hotspotMinDeaths());
        assertEquals(16, Config.hotspotChunkSize());
        assertFalse(Config.hotspotsInDigest());
    }

    @Test
    void configDeclaresAllFourKeysWithTheSpecifiedClamps() throws IOException {
        String config = source("Config.java");
        assertTrue(config.contains("define(\"hotspotsEnabled\", true)"), config);
        assertTrue(config.contains("defineInRange(\"hotspotMinDeaths\", 3, 1, 100)"), config);
        assertTrue(config.contains("defineInRange(\"hotspotChunkSize\", 16, 8, 32)"), config);
        assertTrue(config.contains("define(\"hotspotsInDigest\", false)"), config);
        assertTrue(config.contains("Hidden-coordinate and opted-out deaths are excluded"), config);
        assertTrue(config.contains("the exclusion does not change anonymous server totals"), config);
    }

    @Test
    void commandTreeExposesEveryHotspotArgumentShape() throws IOException {
        // Source check rather than Brigadier registration: on the 26.x trees
        // Commands.<clinit> needs a bootstrapped registry, which a direct javac
        // harness intentionally does not start.
        String commands = source("CensusCommands.java");
        assertTrue(commands.contains("literal(\"hotspots\")"), commands);
        assertTrue(commands.contains("argument(\"dimension\", StringArgumentType.word())"), commands);
        assertTrue(commands.contains("argument(\"page\", IntegerArgumentType.integer(1))"), commands);
        assertTrue(commands.contains("for (String period : PERIODS)"), commands);
        assertTrue(commands.contains("command.deathcensus.hotspots.bad_dimension"), commands);
        assertTrue(commands.contains("command.deathcensus.hotspots.header"), commands);
        assertTrue(commands.contains("command.deathcensus.hotspots.empty"), commands);
        assertTrue(commands.contains("command.deathcensus.hotspots.help"), commands);
        assertTrue(commands.contains("HotspotAnalysis.groupByChunk(inPeriod, filter, 0L,"), commands);
        assertTrue(commands.contains("hotspots(ctx, null, \"all\", 1)"), commands);
    }

    @Test
    void exportAndUiUseTheSharedProjection() throws IOException {
        String export = source("CensusExport.java");
        assertTrue(export.contains("toJsonWithChronicle(players, now"), export);
        assertTrue(export.contains("toCsvWithChronicle(players, anonymize"), export);
        assertTrue(export.contains("toHtmlWithChronicle(players, System.currentTimeMillis()"), export);
        assertTrue(export.contains("hotspotLines(allPlayers)"), export);
        assertTrue(export.contains("command.deathcensus.export.hotspots"), export);
        assertTrue(com.pengin0503.deathcensus.ui.UiSession.RankingSelection.TYPES.contains("hotspots"));

        String contextName = Files.exists(Path.of(System.getProperty("deathcensus.mainSourceDir"),
                "com", "pengin0503", "deathcensus", "ui", "DialogUiContext.java"))
                ? "ui/DialogUiContext.java" : "ui/ChatUiContext.java";
        String uiName = contextName.contains("Dialog") ? "ui/DialogUi.java" : "ui/ChatUi.java";
        assertTrue(source(contextName).contains("return Config.hotspotChunkSize()"), contextName);
        assertTrue(source(uiName).contains("\"command.deathcensus.ui.ranking.hotspots\""), uiName);
    }

    @Test
    void allHotspotKeysExistInBothBundles() throws IOException {
        JsonObject en = loadLang("en_us");
        JsonObject ja = loadLang("ja_jp");
        List<String> keys = List.of(
                "command.deathcensus.hotspots.help",
                "command.deathcensus.hotspots.header",
                "command.deathcensus.hotspots.row",
                "command.deathcensus.hotspots.empty",
                "command.deathcensus.hotspots.bad_dimension",
                "command.deathcensus.ui.ranking.hotspots",
                "command.deathcensus.export.hotspots");
        for (String key : keys) {
            assertTrue(en.has(key), "missing en_us entry for " + key);
            assertTrue(ja.has(key), "missing ja_jp entry for " + key);
            assertFalse(en.get(key).getAsString().isBlank(), "blank en_us entry for " + key);
            assertFalse(ja.get(key).getAsString().isBlank(), "blank ja_jp entry for " + key);
        }
        assertEquals(7, keys.size());
        assertTrue(en.get("command.deathcensus.ui.chat.ranking_hint").getAsString().contains("hotspots"));
        assertTrue(ja.get("command.deathcensus.ui.chat.ranking_hint_no_awards").getAsString().contains("hotspots"));
    }

    @Test
    void commonProjectionExcludesHiddenAndOptedOutLocations() {
        DeathEntry publicDeath = new DeathEntry("p", "Public", 1_000L, 1L, 10, 10, "d",
                1.0, 64.0, 1.0, "fall", "minecraft:fall", "died", "", "", "survival", 0, "", "", 0);
        DeathEntry hiddenDeath = new DeathEntry("h", "Hidden", 1_000L, 1L, 10, 10, "d",
                100.0, 64.0, 100.0, "fall", "minecraft:fall", "died", "", "", "survival", 0, "", "", 0);
        List<PlayerDeaths> snapshot = List.of(
                new PlayerDeaths("p", "Public", false, false, false, false, List.of(publicDeath)),
                new PlayerDeaths("h", "Hidden", false, true, false, false, List.of(hiddenDeath)),
                new PlayerDeaths("o", "OptedOut", true, false, false, false, List.of(hiddenDeath)));
        List<Hotspot> rows = HotspotAnalysis.groupByChunk(snapshot, null, 0L, 16, 1);
        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).count());
        assertEquals(0, rows.get(0).chunkX());
        assertEquals(3, CensusStats.totalDeaths(snapshot));
    }
}
