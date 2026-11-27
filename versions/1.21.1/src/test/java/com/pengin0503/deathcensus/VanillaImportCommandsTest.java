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

/** PR-10 (DESIGN 1.3.0 §9): admin-gated /dc import vanilla [dry-run]. */
class VanillaImportCommandsTest {
    private static String source() throws IOException {
        String dir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(dir);
        return Files.readString(Path.of(dir, "com", "pengin0503", "deathcensus", "CensusCommands.java"));
    }

    private static LiteralCommandNode<CommandSourceStack> registered(
            LiteralArgumentBuilder<CommandSourceStack> node) {
        return new CommandDispatcher<CommandSourceStack>().register(node);
    }

    private static boolean hasChild(LiteralArgumentBuilder<CommandSourceStack> node, String name) {
        return registered(node).getChildren().stream().anyMatch(child -> name.equals(child.getName()));
    }

    @Test
    void commandTreeHasVanillaAndDryRunUnderImportGate() {
        LiteralArgumentBuilder<CommandSourceStack> tree = CensusCommands.importTree(src -> false);
        assertTrue(hasChild(tree, "vanilla"));
        var vanilla = registered(tree).getChild("vanilla");
        assertNotNull(vanilla);
        assertTrue(vanilla.getChildren().stream().anyMatch(child -> "dry-run".equals(child.getName())));
    }

    @Test
    void importGateUsesPermissionBridge() throws IOException {
        String src = source();
        assertTrue(src.contains("PermissionBridge.requires(DeathCensusPermission.IMPORT, opLevelTwoFallback)"));
        assertTrue(src.contains("static LiteralArgumentBuilder<CommandSourceStack> importTree("));
        assertTrue(src.contains("literal(\"dry-run\")"));
        assertTrue(src.contains("root.then(importTree(src -> src.hasPermission(2)));"), "import subtree must be registered with this tree's OP-level-2 fallback");
        assertTrue(src.contains("data.allPlayers().keySet()"));
        assertTrue(src.contains("LevelResource.ROOT).resolve(\"stats\")"));
    }

    @Test
    void realImportIsBackupFirstReadOnlyDryRunAndNeverSynthetic() throws IOException {
        String body = importBody();
        int backup = body.indexOf("CensusExport.writeBackup(server)");
        int apply = body.indexOf("data.importVanillaBaselines(stats, dryRun)");
        assertTrue(backup >= 0 && apply > backup, "backup must precede the baseline merge");
        assertTrue(body.contains("if (!dryRun && CensusExport.writeBackup(server) == null)"),
                "dry run must not write a backup");
        assertTrue(body.contains("fail(ctx, \"command.deathcensus.import.vanilla.failed\")"));
        assertTrue(body.contains("data.importVanillaBaselines(stats, dryRun)"));
        for (String forbidden : List.of("recordDeath", "new DeathEntry", "Milestones", "DiscordNotifier",
                "notifyDeath", "setLastDigestEpochMillis", "setDirty()")) {
            assertFalse(body.contains(forbidden), "import path must not touch " + forbidden);
        }
    }

    @Test
    void diagnosticsAndHelpExposeTheImport() throws IOException {
        String src = source();
        assertEquals(2, src.split("Vanilla baseline: ", -1).length - 1,
                "server stats and doctor each print one literal baseline line");
        assertTrue(src.contains("command.deathcensus.import.vanilla.help"));
        assertTrue(src.contains("command.deathcensus.import.vanilla.per_player"));
        assertTrue(src.contains("command.deathcensus.import.vanilla.dry_run"));
        assertTrue(src.contains("command.deathcensus.import.vanilla.done"));
        assertTrue(src.contains("command.deathcensus.import.vanilla.no_players"));
    }

    @Test
    void bothLanguagesCarryTheImportKeys() throws IOException {
        String[] keys = {
                "command.deathcensus.import.vanilla.done",
                "command.deathcensus.import.vanilla.dry_run",
                "command.deathcensus.import.vanilla.no_players",
                "command.deathcensus.import.vanilla.failed",
                "command.deathcensus.import.vanilla.per_player",
                "command.deathcensus.import.vanilla.help",
                "command.deathcensus.help.admin"
        };
        for (String lang : List.of("en_us", "ja_jp")) {
            JsonObject table = langTable(lang);
            for (String key : keys) {
                assertTrue(table.has(key), lang + " missing " + key);
            }
            assertEquals(295, table.size());
            assertTrue(table.get("command.deathcensus.help.admin").getAsString().contains("import vanilla"));
        }
    }

    @Test
    void dryRunDoesNotCreateOrDirtySavedData() throws IOException {
        String body = importBody();
        assertTrue(body.contains("CensusData data = dryRun ? CensusData.getIfExists(server) : CensusData.get(server)"),
                "dry run must use a non-creating data lookup");
        assertTrue(body.contains("data == null ? java.util.Set.of()"));
        assertTrue(body.contains("new VanillaImportResult(java.util.List.of(), 0, 0L)"));
        assertFalse(body.contains("setDirty"));
    }

    private static String importBody() throws IOException {
        String src = source();
        int start = src.indexOf("private static int importVanilla(");
        assertTrue(start >= 0, "import handler not found");
        int end = src.indexOf("\n    }\n", start);
        assertTrue(end > start, "import handler end not found");
        return src.substring(start, end);
    }

    private static JsonObject langTable(String lang) throws IOException {
        try (InputStream in = VanillaImportCommandsTest.class.getResourceAsStream(
                "/assets/deathcensus/lang/" + lang + ".json")) {
            assertNotNull(in, lang);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }
}
