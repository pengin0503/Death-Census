package com.pengin0503.deathcensus;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pengin0503.deathcensus.ui.UiSession;
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

/** Per-tree compile/wiring check for the 1.4.0 PR-5 chronicle surface (§3). */
class ChronicleWiringTest {
    private static String source(String file) throws IOException {
        String dir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(dir, "deathcensus.mainSourceDir system property is not set");
        return Files.readString(Path.of(dir, "com", "pengin0503", "deathcensus", file));
    }

    private static JsonObject loadLang(String lang) throws IOException {
        String path = "/assets/deathcensus/lang/" + lang + ".json";
        try (InputStream in = ChronicleWiringTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing lang resource " + path);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    @Test
    void configDefaultsMatchTheRecommendedValues() {
        assertTrue(Config.chronicleEnabled());
        assertEquals(200, Config.chronicleMaxEvents());
        assertTrue(Config.chronicleInExport());
        assertFalse(Config.chronicleInDiscord());
    }

    @Test
    void configDeclaresAllFourKeysWithTheSpecifiedClamp() throws IOException {
        String config = source("Config.java");
        assertTrue(config.contains("define(\"chronicleEnabled\", true)"), config);
        assertTrue(config.contains("defineInRange(\"chronicleMaxEvents\", 200, 0, 1000)"), config);
        assertTrue(config.contains("define(\"chronicleInExport\", true)"), config);
        assertTrue(config.contains("define(\"chronicleInDiscord\", false)"), config);
    }

    @Test
    void hotPathRecordsThroughTheRulesAndBoundedSeam() throws IOException {
        String tracker = source("DeathTracker.java");
        assertTrue(tracker.contains("if (Config.chronicleEnabled())"), tracker);
        assertTrue(tracker.contains("ChronicleRules.eventsFor(entry,"), tracker);
        assertTrue(tracker.contains("playerDistinctCategoriesBefore"), tracker);
        assertTrue(tracker.contains("data.addChronicleEvents(additions)"), tracker);
        String data = source("CensusData.java");
        assertTrue(data.contains("addChronicleEvents(Collection<ChronicleEvent> additions)"), data);
        assertTrue(data.contains("ChronicleRules.cap"), data);
        assertTrue(data.contains("reconstructChronicle(data, lifetimeDeathsPresent, storedCategories)"), data);
        // M1: reconstruction must honor the configured retention cap.
        assertTrue(data.contains("storedCategories),\n                Config.chronicleMaxEvents());"), data);
        // M4: undated reconstructed rows use the dedicated lang key.
        assertTrue(source("CensusCommands.java").contains(
                "command.deathcensus.chronicle.row_undated"), "chat chronicle rows must use row_undated");
        assertTrue(data.contains("e.reconstructed()"), data);
    }

    @Test
    void commandUsesPublicProjectionPaginationAndDiscordSeam() throws IOException {
        String commands = source("CensusCommands.java");
        assertTrue(commands.contains("literal(\"chronicle\")"), commands);
        assertTrue(commands.contains("for (String order : List.of(\"oldest\", \"newest\"))"), commands);
        assertTrue(commands.contains("Pagination.of(rows, page, PAGE_SIZE)"), commands);
        assertTrue(commands.contains("command.deathcensus.chronicle.header"), commands);
        assertTrue(commands.contains("command.deathcensus.chronicle.empty"), commands);
        assertTrue(commands.contains("command.deathcensus.chronicle.help"), commands);
        assertTrue(commands.contains("ChronicleRules.forPublic(row, privacy, hidden, privileged)"), commands);
        assertTrue(commands.contains("HiddenIdentities.of(snapshot)"), commands);
        assertTrue(commands.contains("ChronicleRules.forDiscord(projected, privacy)"), commands);
        assertTrue(commands.contains("Config.chronicleInDiscord()"), commands);
    }

    @Test
    void exportsAndGuiAreGated() throws IOException {
        String export = source("CensusExport.java");
        assertTrue(export.contains("if (!Config.chronicleInExport())"), export);
        assertTrue(export.contains("CensusCommands.chronicleLines("), export);
        assertTrue(export.contains("toJsonWithChronicle("), export);
        assertTrue(export.contains("toCsvWithChronicle("), export);
        assertTrue(export.contains("toHtmlWithChronicle("), export);
        assertTrue(export.contains("command.deathcensus.export.chronicle"), export);

        String dir = System.getProperty("deathcensus.mainSourceDir");
        String uiFile = Files.exists(Path.of(dir, "com", "pengin0503", "deathcensus",
                "ui", "DialogUiContext.java")) ? "ui/DialogUiContext.java" : "ui/ChatUiContext.java";
        String ui = source(uiFile);
        assertTrue(ui.contains("public boolean chronicleEnabled()"), ui);
        assertTrue(ui.contains("public List<ChronicleEvent> chronicleEvents()"), ui);
        assertTrue(ui.contains("Config.chronicleEnabled()"), ui);
        String adapter = source(uiFile.contains("Dialog")
                ? "ui/DialogUi.java" : "ui/ChatUi.java");
        assertTrue(adapter.contains("command.deathcensus.ui.ranking.chronicle"), adapter);
        if (uiFile.contains("Dialog")) {
            assertTrue(adapter.contains("types.add(\"chronicle\")"), adapter);
        }
        assertTrue(UiSession.RankingSelection.TYPES.contains("chronicle"),
                "RankingSelection.TYPES must accept chronicle");
    }

    @Test
    void bothLangBundlesCarryEveryChronicleKey() throws IOException {
        JsonObject en = loadLang("en_us");
        JsonObject ja = loadLang("ja_jp");
        List<String> keys = List.of(
                "command.deathcensus.chronicle.help", "command.deathcensus.chronicle.header",
                "command.deathcensus.chronicle.empty", "command.deathcensus.chronicle.row",
                "command.deathcensus.chronicle.reconstructed", "command.deathcensus.chronicle.hidden",
                "command.deathcensus.chronicle.kind.server_first",
                "command.deathcensus.chronicle.kind.nether_first",
                "command.deathcensus.chronicle.kind.end_dragon_death",
                "command.deathcensus.chronicle.kind.total_count",
                "command.deathcensus.chronicle.kind.category_complete",
                "command.deathcensus.export.chronicle",
                "command.deathcensus.ui.ranking.chronicle");
        assertEquals(13, keys.size());
        for (String key : keys) {
            assertTrue(en.has(key), "missing en_us entry for " + key);
            assertTrue(ja.has(key), "missing ja_jp entry for " + key);
            assertFalse(en.get(key).getAsString().isBlank(), "blank en_us entry for " + key);
            assertFalse(ja.get(key).getAsString().isBlank(), "blank ja_jp entry for " + key);
        }
    }
}
