package com.pengin0503.deathcensus;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pengin0503.deathcensus.ui.UiRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-tree wiring check for the 1.4.0 PR-12 compare surfaces (§6). */
class CompareWiringTest {
    private static final UUID A_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID B_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static String source(String file) throws IOException {
        String dir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(dir, "deathcensus.mainSourceDir system property is not set");
        return Files.readString(Path.of(dir, "com", "pengin0503", "deathcensus", file));
    }

    private static JsonObject loadLang(String lang) throws IOException {
        String path = "/assets/deathcensus/lang/" + lang + ".json";
        try (InputStream in = CompareWiringTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing lang resource " + path);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    @Test
    void configDefaultsMatchTheRecommendedValues() {
        assertTrue(Config.compareEnabled());
        assertEquals("all", Config.compareDefaultPeriod());
        assertFalse(Config.compareInDiscord());
    }

    @Test
    void configDeclaresAllThreeKeys() throws IOException {
        String config = source("Config.java");
        assertTrue(config.contains("define(\"compareEnabled\", true)"), config);
        assertTrue(config.contains("define(\"compareDefaultPeriod\", \"all\", Config::validateComparePeriod)"), config);
        assertTrue(config.contains("define(\"compareInDiscord\", false)"), config);
        assertTrue(config.contains("validateComparePeriod"), config);
    }

    @Test
    void commandTreeExposesEveryCompareArgumentShape() throws IOException {
        String commands = source("CensusCommands.java");
        assertTrue(commands.contains("literal(\"compare\")"), commands);
        assertTrue(commands.contains("arg(\"playerA\", StringArgumentType.word())"), commands);
        assertTrue(commands.contains("arg(\"playerB\", StringArgumentType.word())"), commands);
        assertTrue(commands.contains("Config.compareDefaultPeriod()"), commands);
        assertTrue(commands.contains("literal(\"--html\")"), commands);
        assertTrue(commands.contains("for (String period : PERIODS)"), commands);
        assertTrue(commands.contains("command.deathcensus.compare.header"), commands);
        assertTrue(commands.contains("command.deathcensus.compare.unknown"), commands);
        assertTrue(commands.contains("command.deathcensus.compare.html"), commands);
    }

    @Test
    void commandPassesViewerIdentityAndAdminDecisionToCommonGate() throws IOException {
        String commands = source("CensusCommands.java");
        assertTrue(commands.contains("CompareAnalysis.compare(snapshot, nameA, nameB, sinceMillis(period),"),
                commands);
        assertTrue(commands.contains("selfUuid(ctx), isAdmin(ctx)"), commands);
        assertTrue(commands.contains("WitnessRedactor.privacyById(snapshot)"), commands);
        // The failure is deliberately rendered through a single key for both reasons.
        assertTrue(commands.contains("fail(ctx, \"command.deathcensus.compare.unknown\")"), commands);
    }

    @Test
    void bothAdaptersWireTheCompareView() throws IOException {
        String adapter = Files.exists(Path.of(System.getProperty("deathcensus.mainSourceDir"),
                "com", "pengin0503", "deathcensus", "ui", "DialogUi.java"))
                ? source("ui/DialogUi.java") : source("ui/ChatUi.java");
        assertTrue(adapter.contains("instanceof CompareView"), adapter);
        assertTrue(adapter.contains("command.deathcensus.ui.compare.open"), adapter);
        assertTrue(adapter.contains("command.deathcensus.ui.ranking.compare"), adapter);
        String context = Files.exists(Path.of(System.getProperty("deathcensus.mainSourceDir"),
                "com", "pengin0503", "deathcensus", "ui", "DialogUiContext.java"))
                ? source("ui/DialogUiContext.java") : source("ui/ChatUiContext.java");
        assertTrue(context.contains("public boolean compareEnabled()"), context);
        assertTrue(context.contains("Config.compareEnabled()"), context);
    }

    @Test
    void htmlReportIsWrittenOnlyIntoExports() throws IOException {
        String export = source("CensusExport.java");
        assertTrue(export.contains("public static Path writeCompareHtml("), export);
        assertTrue(export.contains("exportsDir(server).resolve(\"compare-\""), export);
        assertTrue(export.contains("compare-" + ""), export);
        assertTrue(export.contains("ExportRenderers.compareHtml("), export);
        int start = export.indexOf("writeCompareHtml(");
        int end = export.indexOf("/** Writes a JSON backup", start);
        assertTrue(end > start, export);
        assertFalse(export.substring(start, end).contains("backupsDir"), export);
    }

    @Test
    void compareInputsAreAcceptedByTheUiProtocol() throws IOException {
        String adapter = Files.exists(Path.of(System.getProperty("deathcensus.mainSourceDir"),
                "com", "pengin0503", "deathcensus", "ui", "ChatUiRequestParser.java"))
                ? source("ui/ChatUiRequestParser.java") : source("ui/DialogClickHandler.java");
        assertTrue(adapter.contains("in_compare_a"), adapter);
        assertTrue(adapter.contains("in_compare_b"), adapter);
        if (adapter.contains("parseCompare")) {
            // Reflective so the same test class also compiles in the 26.x trees,
            // which have no chat-UI parser.
            try {
                Class<?> parser = Class.forName("com.pengin0503.deathcensus.ui.ChatUiRequestParser");
                Object parsed = parser.getMethod("parse", UUID.class, String[].class)
                        .invoke(null, A_ID, (Object) new String[]{"compare", "Alice", "Bob", "7d", "token"});
                assertTrue(parsed instanceof java.util.Optional<?> optional && optional.isPresent());
                UiRequest request = (UiRequest) ((java.util.Optional<?>) parsed).orElseThrow();
                assertEquals("compare", request.action());
                assertEquals("Alice", request.inputs().get("in_compare_a"));
                assertEquals("Bob", request.inputs().get("in_compare_b"));
                assertEquals("7d", request.inputs().get("in_period"));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }

    @Test
    void langBundlesCoverEveryCompareKeyWithParity() throws IOException {
        JsonObject en = loadLang("en_us");
        JsonObject ja = loadLang("ja_jp");
        assertEquals(en.keySet(), ja.keySet());
        assertEquals(313, en.size());
        for (String key : List.of(
                "command.deathcensus.compare.help", "command.deathcensus.compare.header",
                "command.deathcensus.compare.row.deaths",
                "command.deathcensus.compare.row.average_survival",
                "command.deathcensus.compare.row.longest_survival",
                "command.deathcensus.compare.row.shortest_survival",
                "command.deathcensus.compare.row.top_cause",
                "command.deathcensus.compare.row.player_deaths",
                "command.deathcensus.compare.row.top_opponent",
                "command.deathcensus.compare.anonymous",
                "command.deathcensus.compare.unknown", "command.deathcensus.compare.html",
                "command.deathcensus.ui.ranking.compare",
                "command.deathcensus.ui.compare.title",
                "command.deathcensus.ui.compare.header",
                "command.deathcensus.ui.compare.empty",
                "command.deathcensus.ui.compare.input.a",
                "command.deathcensus.ui.compare.input.b",
                "command.deathcensus.ui.compare.open")) {
            assertTrue(en.has(key), "missing " + key);
            assertTrue(ja.has(key), "missing ja " + key);
        }
    }

    @Test
    void oneSidedOptOutHidesBothNamesButAdminMayCompare() {
        long now = 1_700_000_000_000L;
        DeathEntry death = new DeathEntry(A_ID.toString(), "Alice", now, 1L, 10, 10,
                "minecraft:overworld", 0.0, 64.0, 0.0, "fall", "fall:id", "died", "", "",
                "survival", 0, "", "", 0);
        List<PlayerDeaths> players = Arrays.asList(
                new PlayerDeaths(A_ID.toString(), "Alice", true, false, false, false, List.of(death)),
                new PlayerDeaths(B_ID.toString(), "Bob", false, false, false, false, List.of(death)));
        CompareResult publicResult = CompareAnalysis.compare(players, "Alice", "Bob", 0L,
                Map.of(), UUID.randomUUID(), false);
        assertEquals(CompareResult.Failure.HIDDEN_PLAYER, publicResult.failure());
        assertTrue(CompareAnalysis.compare(players, "Alice", "Bob", 0L,
                Map.of(), UUID.randomUUID(), true).ok());
    }

    @Test
    void noStorageOrEventChangesAreNeeded() throws IOException {
        String data = source("CensusData.java");
        assertFalse(data.contains("compareEnabled"), data);
        assertFalse(data.contains("CompareResult"), data);
    }
}
