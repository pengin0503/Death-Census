package com.pengin0503.deathcensus;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
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

/** PR-9 (DESIGN 1.3.0 §8): double-gated /dc dev demo <count>, backup-first and silent. */
class DemoCommandsTest {
    private static String source() throws IOException {
        String dir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(dir);
        return Files.readString(Path.of(dir, "com", "pengin0503", "deathcensus", "CensusCommands.java"));
    }

    private static LiteralCommandNode<CommandSourceStack> registered(
            LiteralArgumentBuilder<CommandSourceStack> dev) {
        return new CommandDispatcher<CommandSourceStack>().register(dev);
    }

    private static boolean hasChild(LiteralArgumentBuilder<CommandSourceStack> node, String name) {
        return registered(node).getChildren().stream().anyMatch(child -> name.equals(child.getName()));
    }

    @Test
    void gateMatrixControlsRegistration() {
        // neither / config only / property only: refusing stub, no help branch;
        // both: the real demo command tree with its help branch.
        for (boolean[] combo : new boolean[][]{{false, false}, {true, false}, {false, true}}) {
            LiteralArgumentBuilder<CommandSourceStack> dev =
                    CensusCommands.devTree(combo[0], combo[1] ? "true" : null, src -> false);
            assertFalse(hasChild(dev, "help"), "gate must be closed for " + combo[0] + "/" + combo[1]);
            assertEquals(1, registered(dev).getChildren().stream().filter(c -> "demo".equals(c.getName())).count());
        }
        LiteralArgumentBuilder<CommandSourceStack> open =
                CensusCommands.devTree(true, "true", src -> true);
        assertTrue(hasChild(open, "help"));
        assertEquals(1, registered(open).getChildren().stream().filter(c -> "demo".equals(c.getName())).count());
        // The demo node accepts the bounded count argument in both modes.
        for (LiteralArgumentBuilder<CommandSourceStack> dev : List.of(open,
                CensusCommands.devTree(false, null, src -> false))) {
            assertTrue(registered(dev).getChildren().stream()
                    .filter(c -> "demo".equals(c.getName()))
                    .anyMatch(c -> c.getChildren().stream().anyMatch(a -> "count".equals(a.getName()))));
        }
    }

    @Test
    void commandWiringUsesDevNodeAndDoubleGate() throws IOException {
        String src = source();
        assertTrue(src.contains("root.then(devTree(Config.devMode(), "
                + "System.getProperty(DemoGate.SYSTEM_PROPERTY)"));
        assertTrue(src.contains("PermissionBridge.requires(DeathCensusPermission.DEV, opLevelTwoFallback)"));
        assertTrue(src.contains("IntegerArgumentType.integer(1, 500)"));
        assertTrue(src.contains("literal(\"demo\")"));
        assertTrue(src.contains("DemoGate.enabled(configFlag, systemProperty)"));
        assertTrue(src.contains("DemoGate.enabled(Config.devMode(), "
                + "System.getProperty(DemoGate.SYSTEM_PROPERTY))"));
        assertFalse(Config.devMode(), "devMode must default to false before config load");
    }

    @Test
    void backupIsWrittenBeforeAnyInsertionAndAbortsOnFailure() throws IOException {
        String demo = demoBody();
        int backup = demo.indexOf("CensusExport.writeBackup(server)");
        int generate = demo.indexOf("DemoDataGenerator.generate(");
        int insert = demo.indexOf("data.recordDeath(");
        assertTrue(backup >= 0 && generate > backup && insert > generate,
                "backup must precede generation and insertion");
        assertTrue(demo.contains("if (CensusExport.writeBackup(server) == null)"));
        assertTrue(demo.contains("command.deathcensus.delete.backup_failed"));
        int failureReturn = demo.indexOf("return 0;", backup);
        assertTrue(failureReturn > backup && failureReturn < generate,
                "failed backup must abort before generating data");
    }

    @Test
    void demoPathHasNoDiscordOrMilestoneSideEffects() throws IOException {
        String demo = demoBody();
        for (String forbidden : List.of("DiscordNotifier", "Milestones", "notifyDeath", "notifyMilestone",
                "announceMilestone", "checkAndSendDigest", "setLastDigestEpochMillis", "sendDeathLine",
                "DeathTracker")) {
            assertFalse(demo.contains(forbidden), "demo path must not touch " + forbidden);
        }
        String src = source();
        assertTrue(src.contains("[DeathCensus] DEMO DATA GENERATED: {} synthetic deaths (source: {})"));
        assertTrue(src.contains("ctx.getSource().getTextName()"));
    }

    @Test
    void generatedRecordsAreOrdinaryAndCleanupIsDocumented() throws IOException {
        String demo = demoBody();
        assertTrue(demo.contains("CensusData.get(server)"));
        assertTrue(demo.contains("data.recordDeath(entry, Config.maxRecordsPerPlayer())"));
        assertTrue(demo.contains("DemoDataGenerator.DEFAULT_SEED"));
        assertTrue(source().contains("literal(\"help\").executes(CensusCommands::devHelp)"));
        assertTrue(lang("command.deathcensus.dev.help").contains("/dc reset all confirm"));
    }

    @Test
    void bothLanguagesCarryTheDemoKeys() throws IOException {
        String[] keys = {
                "command.deathcensus.dev.gated",
                "command.deathcensus.dev.demo.done",
                "command.deathcensus.dev.demo.count",
                "command.deathcensus.dev.help",
                "command.deathcensus.help.admin"
        };
        for (String lang : List.of("en_us", "ja_jp")) {
            JsonObject table = langTable(lang);
            for (String key : keys) {
                assertTrue(table.has(key), lang + " missing " + key);
            }
            assertEquals(198, table.size());
        }
    }

    private static String demoBody() throws IOException {
        String src = source();
        int start = src.indexOf("private static int demo(");
        assertTrue(start >= 0, "demo handler not found");
        int end = src.indexOf("\n    }\n", start);
        assertTrue(end > start, "demo handler end not found");
        return src.substring(start, end);
    }

    private static String lang(String key) throws IOException {
        return langTable("en_us").get(key).getAsString();
    }

    private static JsonObject langTable(String lang) throws IOException {
        try (InputStream in = DemoCommandsTest.class.getResourceAsStream(
                "/assets/deathcensus/lang/" + lang + ".json")) {
            assertNotNull(in, lang);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }
}
