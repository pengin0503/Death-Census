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

/** Per-tree wiring check for the 1.4.0 PR-8 distribution surfaces (§7). */
class DistributionWiringTest {
    private static String source(String file) throws IOException {
        String dir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(dir, "deathcensus.mainSourceDir system property is not set");
        return Files.readString(Path.of(dir, "com", "pengin0503", "deathcensus", file));
    }

    private static JsonObject loadLang(String lang) throws IOException {
        String path = "/assets/deathcensus/lang/" + lang + ".json";
        try (InputStream in = DistributionWiringTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing lang resource " + path);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    @Test
    void configDefaultsMatchTheRecommendedValues() {
        assertTrue(Config.distributionsEnabled());
        assertEquals(2, Config.distributionTimeBinHours());
        assertEquals(16, Config.distributionYBandBlocks());
        assertFalse(Config.distributionsInDigest());
    }

    @Test
    void configDeclaresAllFourKeysWithTheSpecifiedClamps() throws IOException {
        String config = source("Config.java");
        assertTrue(config.contains("define(\"distributionsEnabled\", true)"), config);
        assertTrue(config.contains("defineInRange(\"distributionTimeBinHours\", 2, 1, 12)"), config);
        assertTrue(config.contains("defineInRange(\"distributionYBandBlocks\", 16, 8, 64)"), config);
        assertTrue(config.contains("define(\"distributionsInDigest\", false)"), config);
        assertTrue(config.contains("time bins count opted-out deaths, Y bands exclude hidden-coordinate deaths"), config);
    }

    @Test
    void commandTreeExposesEveryDistributionArgumentShape() throws IOException {
        // Source check rather than Brigadier registration: on the 26.x trees
        // Commands.<clinit> needs a bootstrapped registry, which a direct javac
        // harness intentionally does not start.
        String commands = source("CensusCommands.java");
        assertTrue(commands.contains("literal(\"distribution\")"), commands);
        assertTrue(commands.contains("List.of(\"time\", \"y\")"), commands);
        assertTrue(commands.contains("literal(mode)"), commands);
        assertTrue(commands.contains("argument(\"page\", IntegerArgumentType.integer(1))"), commands);
        assertTrue(commands.contains("for (String period : PERIODS)"), commands);
        assertTrue(commands.contains("command.deathcensus.distribution.bad_argument"), commands);
        assertTrue(commands.contains("command.deathcensus.distribution.header.time"), commands);
        assertTrue(commands.contains("command.deathcensus.distribution.header.y"), commands);
        assertTrue(commands.contains("DistributionAnalysis.timeOfDay(snapshot,"), commands);
        assertTrue(commands.contains("DistributionAnalysis.yBands(snapshot,"), commands);
        assertTrue(commands.contains("distribution(ctx, mode, \"all\", 1)"), commands);
    }

    @Test
    void exportAndUiUseTheSharedProjection() throws IOException {
        String export = source("CensusExport.java");
        assertTrue(export.contains("toJsonWithChronicle(players, now"), export);
        assertTrue(export.contains("toCsvWithChronicle(players, anonymize"), export);
        assertTrue(export.contains("toHtmlWithChronicle(players, System.currentTimeMillis()"), export);
        assertTrue(export.contains("CensusCommands.publicDistributionLines(allPlayers)"), export);
        assertTrue(export.contains("command.deathcensus.export.distribution"), export);
        assertTrue(com.pengin0503.deathcensus.ui.UiSession.RankingSelection.TYPES.contains("distribution_time"));
        assertTrue(com.pengin0503.deathcensus.ui.UiSession.RankingSelection.TYPES.contains("distribution_y"));

        String contextName = Files.exists(Path.of(System.getProperty("deathcensus.mainSourceDir"),
                "com", "pengin0503", "deathcensus", "ui", "DialogUiContext.java"))
                ? "ui/DialogUiContext.java" : "ui/ChatUiContext.java";
        String uiName = contextName.contains("Dialog") ? "ui/DialogUi.java" : "ui/ChatUi.java";
        assertTrue(source(contextName).contains("return Config.distributionTimeBinHours()"), contextName);
        assertTrue(source(contextName).contains("return Config.distributionYBandBlocks()"), contextName);
        assertTrue(source(uiName).contains("\"command.deathcensus.ui.ranking.distribution.time\""), uiName);
        assertTrue(source(uiName).contains("\"command.deathcensus.ui.ranking.distribution.y\""), uiName);
    }

    @Test
    void allDistributionKeysExistInBothBundles() throws IOException {
        JsonObject en = loadLang("en_us");
        JsonObject ja = loadLang("ja_jp");
        List<String> keys = List.of(
                "command.deathcensus.distribution.help",
                "command.deathcensus.distribution.header.time",
                "command.deathcensus.distribution.header.y",
                "command.deathcensus.distribution.row",
                "command.deathcensus.distribution.empty",
                "command.deathcensus.distribution.bad_argument",
                "command.deathcensus.ui.ranking.distribution.time",
                "command.deathcensus.ui.ranking.distribution.y",
                "command.deathcensus.export.distribution");
        for (String key : keys) {
            assertTrue(en.has(key), "missing en_us entry for " + key);
            assertTrue(ja.has(key), "missing ja_jp entry for " + key);
            assertFalse(en.get(key).getAsString().isBlank(), "blank en_us entry for " + key);
            assertFalse(ja.get(key).getAsString().isBlank(), "blank ja_jp entry for " + key);
        }
        assertEquals(9, keys.size());
        assertEquals(314, en.size());
        assertEquals(314, ja.size());
        assertTrue(en.get("command.deathcensus.ui.chat.ranking_hint").getAsString().contains("distribution_time"));
        assertTrue(ja.get("command.deathcensus.ui.chat.ranking_hint_no_awards").getAsString().contains("distribution_y"));
    }

    @Test
    void commonProjectionCountsOptedOutDeathsButHidesCoordinateBands() {
        long epoch = 1_700_000_000_000L;
        DeathEntry publicDeath = new DeathEntry("p", "Public", epoch, 1L, 10, 10,
                "minecraft:overworld", 1.0, 64.0, 1.0, "fall", "minecraft:fall", "died",
                "", "", "survival", 0, "", "", 0);
        DeathEntry hiddenDeath = new DeathEntry("h", "Hidden", epoch, 1L, 10, 10,
                "minecraft:overworld", 1.0, 100.0, 1.0, "fall", "minecraft:fall", "died",
                "", "", "survival", 0, "", "", 0);
        List<PlayerDeaths> snapshot = List.of(
                new PlayerDeaths("p", "Public", false, false, false, false, List.of(publicDeath)),
                new PlayerDeaths("o", "OptedOut", true, false, false, false, List.of(publicDeath)),
                new PlayerDeaths("h", "Hidden", false, true, false, false, List.of(hiddenDeath)));
        assertEquals(3, DistributionAnalysis.timeOfDay(snapshot, 2,
                java.time.ZoneOffset.UTC).stream().mapToInt(TimeBin::count).sum());
        List<YBand> bands = DistributionAnalysis.yBands(snapshot, 16);
        assertEquals(1, bands.size());
        assertEquals(2, bands.get(0).count());
        assertEquals(3, CensusStats.totalDeaths(snapshot));
    }
}
