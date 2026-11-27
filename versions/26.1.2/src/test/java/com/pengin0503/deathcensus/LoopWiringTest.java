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

/** Per-tree wiring check for the 1.4.0 PR-9 death-loop surfaces (§8). */
class LoopWiringTest {
    private static String source(String file) throws IOException {
        String dir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(dir, "deathcensus.mainSourceDir system property is not set");
        return Files.readString(Path.of(dir, "com", "pengin0503", "deathcensus", file));
    }

    private static JsonObject loadLang(String lang) throws IOException {
        String path = "/assets/deathcensus/lang/" + lang + ".json";
        try (InputStream in = LoopWiringTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing lang resource " + path);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    @Test
    void configDefaultsMatchSectionFifteen() {
        assertTrue(Config.deathLoopEnabled());
        assertEquals(3, Config.deathLoopMinDeaths());
        assertEquals(10, Config.deathLoopWindowMinutes());
        assertEquals(600_000L, Config.deathLoopWindowMillis());
        assertEquals(48, Config.deathLoopRadiusBlocks());
        assertTrue(Config.deathLoopNotifyVictim());
        assertFalse(Config.loopsInExport());
        assertFalse(Config.loopsInDiscord());
    }

    @Test
    void configDeclaresAllSevenKeysWithTheSpecifiedClamps() throws IOException {
        String config = source("Config.java");
        assertTrue(config.contains("define(\"deathLoopEnabled\", true)"), config);
        assertTrue(config.contains("defineInRange(\"deathLoopMinDeaths\", 3, 1, 100)"), config);
        assertTrue(config.contains("defineInRange(\"deathLoopWindowMinutes\", 10, 1, 60)"), config);
        assertTrue(config.contains("defineInRange(\"deathLoopRadiusBlocks\", 48, 8, 256)"), config);
        assertTrue(config.contains("define(\"deathLoopNotifyVictim\", true)"), config);
        assertTrue(config.contains("define(\"loopsInExport\", false)"), config);
        assertTrue(config.contains("define(\"loopsInDiscord\", false)"), config);
    }

    @Test
    void commandTreeExposesEveryLoopArgumentShape() throws IOException {
        // Source check rather than Brigadier registration: on the 26.x trees
        // Commands.<clinit> needs a bootstrapped registry, which a direct javac
        // harness intentionally does not start.
        String commands = source("CensusCommands.java");
        assertTrue(commands.contains("literal(\"loops\")"), commands);
        assertTrue(commands.contains("argument(\"player\", StringArgumentType.word())"), commands);
        assertTrue(commands.contains("argument(\"page\", IntegerArgumentType.integer(1))"), commands);
        assertTrue(commands.contains("loops(ctx, null, 1)"), commands);
        assertTrue(commands.contains("DeathLoopDetector.findLoops(scope,"), commands);
        assertTrue(commands.contains("command.deathcensus.loops.header"), commands);
        assertTrue(commands.contains("command.deathcensus.loops.row"), commands);
        assertTrue(commands.contains("command.deathcensus.loops.help"), commands);
        assertTrue(commands.contains("publicLoopLines"), commands);
    }

    @Test
    void hotPathUsesOnlyTheTransientRingAndNotifiesTheVictimAlone() throws IOException {
        String trackerSource = source("DeathTracker.java");
        assertTrue(trackerSource.contains("private static final DeathLoopTracker LOOP_TRACKER"), trackerSource);
        assertTrue(trackerSource.contains("LOOP_TRACKER.observe(mark,"), trackerSource);
        assertTrue(trackerSource.contains("Config.deathLoopNotifyVictim()"), trackerSource);
        assertTrue(trackerSource.contains("player.sendSystemMessage(ServerLang.tr(player,"), trackerSource);
        assertTrue(trackerSource.contains("command.deathcensus.loops.detected"), trackerSource);
        assertTrue(trackerSource.contains("LOOP_TRACKER.clear()"), trackerSource);
        assertTrue(trackerSource.contains("!pc.optOutPublic && !pc.hideCoordinates"), trackerSource);
        assertFalse(trackerSource.contains("broadcastSystemMessage(ServerLang.tr(player,\n                            \"command.deathcensus.loops.detected\""), trackerSource);
    }

    @Test
    void exportAndUiUseTheSharedProjection() throws IOException {
        String export = source("CensusExport.java");
        assertTrue(export.contains("toJsonWithChronicle(players, now"), export);
        assertTrue(export.contains("toCsvWithChronicle(players, anonymize"), export);
        assertTrue(export.contains("toHtmlWithChronicle(players, System.currentTimeMillis()"), export);
        assertTrue(export.contains("CensusCommands.publicLoopLines(allPlayers)"), export);
        assertTrue(export.contains("command.deathcensus.export.loops"), export);
        assertTrue(com.pengin0503.deathcensus.ui.UiSession.RankingSelection.TYPES.contains("loops"));

        String contextName = Files.exists(Path.of(System.getProperty("deathcensus.mainSourceDir"),
                "com", "pengin0503", "deathcensus", "ui", "DialogUiContext.java"))
                ? "ui/DialogUiContext.java" : "ui/ChatUiContext.java";
        String uiName = contextName.contains("Dialog") ? "ui/DialogUi.java" : "ui/ChatUi.java";
        assertTrue(source(contextName).contains("return Config.deathLoopEnabled()"), contextName);
        assertTrue(source(contextName).contains("return Config.deathLoopRadiusBlocks()"), contextName);
        assertTrue(source(uiName).contains("\"command.deathcensus.ui.ranking.loops\""), uiName);
        if (contextName.contains("Dialog")) {
            assertTrue(source(uiName).contains("types.add(\"loops\")"), uiName);
        }
    }

    @Test
    void allLoopKeysExistInBothBundles() throws IOException {
        JsonObject en = loadLang("en_us");
        JsonObject ja = loadLang("ja_jp");
        List<String> keys = List.of(
                "command.deathcensus.loops.help",
                "command.deathcensus.loops.header",
                "command.deathcensus.loops.row",
                "command.deathcensus.loops.empty",
                "command.deathcensus.loops.detected",
                "command.deathcensus.export.loops",
                "command.deathcensus.ui.ranking.loops");
        for (String key : keys) {
            assertTrue(en.has(key), "missing en_us entry for " + key);
            assertTrue(ja.has(key), "missing ja_jp entry for " + key);
            assertFalse(en.get(key).getAsString().isBlank(), "blank en_us entry for " + key);
            assertFalse(ja.get(key).getAsString().isBlank(), "blank ja_jp entry for " + key);
        }
        assertEquals(7, keys.size());
        assertEquals(315, en.size());
        assertEquals(315, ja.size());
        assertTrue(en.get("command.deathcensus.ui.chat.ranking_hint").getAsString().contains("loops"));
        assertTrue(ja.get("command.deathcensus.ui.chat.ranking_hint_no_awards").getAsString().contains("loops"));
    }

    @Test
    void commonDetectorExcludesHiddenAndOptedOutDeathsFromPublicRows() {
        long epoch = 1_700_000_000_000L;
        DeathEntry a = new DeathEntry("p", "Public", epoch, 1L, 10, 10,
                "minecraft:overworld", 1.0, 64.0, 1.0, "fall", "minecraft:fall", "died",
                "", "", "survival", 0, "", "", 0);
        DeathEntry b = new DeathEntry("p", "Public", epoch + 60_000L, 1L, 10, 10,
                "minecraft:overworld", 2.0, 64.0, 1.0, "fall", "minecraft:fall", "died",
                "", "", "survival", 0, "", "", 0);
        DeathEntry c = new DeathEntry("p", "Public", epoch + 120_000L, 1L, 10, 10,
                "minecraft:overworld", 3.0, 64.0, 1.0, "fall", "minecraft:fall", "died",
                "", "", "survival", 0, "", "", 0);
        List<PlayerDeaths> snapshot = List.of(
                new PlayerDeaths("p", "Public", false, false, false, false, List.of(a, b, c)),
                new PlayerDeaths("o", "OptedOut", true, false, false, false, List.of(a, b, c)),
                new PlayerDeaths("h", "Hidden", false, true, false, false, List.of(a, b, c)));
        List<DeathLoop> loops = DeathLoopDetector.findLoops(snapshot,
                48, 10 * 60_000L, 3);
        assertEquals(1, loops.size());
        assertEquals("p", loops.get(0).uuid());
        assertEquals(3, loops.get(0).count());
        assertEquals(2, loops.get(0).centerX());
        assertEquals(9, CensusStats.totalDeaths(snapshot));
    }
}
