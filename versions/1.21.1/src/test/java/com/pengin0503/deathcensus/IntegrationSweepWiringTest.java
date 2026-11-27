package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-tree PR-13 wiring checks: help, doctor, ranking navigation and export parity. */
class IntegrationSweepWiringTest {
    private static String source(String file) throws IOException {
        String dir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(dir, "deathcensus.mainSourceDir system property is not set");
        return Files.readString(Path.of(dir, "com", "pengin0503", "deathcensus", file));
    }

    private static Path sourcePath(String file) {
        return Path.of(System.getProperty("deathcensus.mainSourceDir"),
                "com", "pengin0503", "deathcensus", file);
    }

    @Test
    void helpListsCompareAndRespawnBehindTheirConfigGates() throws IOException {
        String commands = source("CensusCommands.java");
        assertTrue(commands.contains("send(ctx, \"command.deathcensus.compare.help\")"), commands);
        assertTrue(commands.contains("send(ctx, \"command.deathcensus.help.respawn\")"), commands);
        int compare = commands.indexOf("command.deathcensus.compare.help");
        int respawn = commands.indexOf("command.deathcensus.help.respawn");
        assertTrue(compare > 0 && respawn > compare, "help order: " + compare + "/" + respawn);
        String helpBody = commands.substring(commands.indexOf("private static int help("),
                commands.indexOf("private static int excuse(", compare));
        assertTrue(helpBody.contains("if (Config.compareEnabled())"), helpBody);
        assertTrue(helpBody.contains("if (Config.respawnGuideEnabled())"), helpBody);
    }

    @Test
    void consoleAndPlayerEntryShareTheSameCompleteHelp() throws IOException {
        String commands = source("CensusCommands.java");
        if (Files.exists(sourcePath("ui/DialogUi.java"))) {
            assertTrue(commands.contains("return help(ctx);"), commands);
        } else {
            int entry = commands.indexOf("private static int entry(");
            int end = commands.indexOf("private static LiteralArgumentBuilder", entry);
            String body = commands.substring(entry, end > entry ? end : entry + 800);
            assertTrue(body.contains("return help(ctx);"), body);
            assertFalse(body.contains("sendHelpLines"), body);
            assertFalse(source("ui/ChatUi.java").contains("sendHelpLines"));
        }
    }

    @Test
    void doctorReportsEveryNewFeatureAndSeamWithoutSecrets() throws IOException {
        String commands = source("CensusCommands.java");
        int start = commands.indexOf("private static int doctor(");
        assertTrue(start >= 0, "doctor is missing");
        int end = commands.indexOf("private static int reload(", start);
        String doctor = commands.substring(start, end);
        assertTrue(doctor.contains("1.4.0 features:"), doctor);
        assertTrue(doctor.contains("1.4.0 seams:"), doctor);
        assertTrue(doctor.contains("1.4.0 aggregates:"), doctor);
        for (String accessor : new String[]{"captureWitnesses", "captureDeathContext", "awardsEnabled",
                "chronicleEnabled", "nemesisEnabled", "hotspotsEnabled", "distributionsEnabled",
                "deathLoopEnabled", "compareEnabled", "respawnGuideEnabled", "nearDeathCaptureEnabled",
                "awardsInDigest", "chronicleInExport", "chronicleInDiscord", "nemesisInDigest",
                "hotspotsInDigest", "distributionsInDigest", "compareInDiscord", "loopsInExport",
                "loopsInDiscord", "witnessesInDiscord", "nearDeathInDiscord", "deathContextInDiscord",
                "deathContextInExports", "nearDeathMaxPerPlayer"}) {
            assertTrue(doctor.contains("Config." + accessor + "()"), "doctor omits " + accessor);
        }
        assertTrue(doctor.contains("data.chronicleEvents().size()"), doctor);
        assertTrue(doctor.contains("nearDeaths"), doctor);
        // Webhook values are converted to status words before any sendLiteral.
        assertTrue(doctor.contains("webhookStatus("), doctor);
        assertFalse(doctor.contains("+ primary"), doctor);
        assertFalse(doctor.contains("+ digestUrl"), doctor);
        assertFalse(doctor.contains("http"), doctor);
    }

    @Test
    void rankingTypeListsIncludeNeardeathInEveryUi() throws IOException {
        String commands = source("CensusCommands.java");
        String ui = Files.exists(sourcePath("ui/DialogUi.java"))
                ? source("ui/DialogUi.java") : source("ui/ChatUi.java");
        if (Files.exists(sourcePath("ui/DialogUi.java"))) {
            assertTrue(ui.contains("types.add(\"neardeath\")"), ui);
            assertTrue(ui.contains("Config.nearDeathCaptureEnabled()"), ui);
        } else {
            assertTrue(ui.contains("command.deathcensus.ui.chat.ranking_hint"), ui);
            assertTrue(ui.contains("command.deathcensus.ui.chat.ranking_hint_no_awards"), ui);
        }
        assertTrue(commands.contains("Config.nearDeathCaptureEnabled()"), commands);
    }

    @Test
    void everyExportFormatCarriesTheLoopSectionInTheSameOrder() throws IOException {
        String export = source("CensusExport.java");
        assertEquals(2, export.split("distributionLines, distributionHeader, loopLines, loopHeader", -1).length - 1,
                "JSON and CSV must both pass the loop section");
        assertTrue(export.contains("Config.loopsInExport()"), export);
    }
}
