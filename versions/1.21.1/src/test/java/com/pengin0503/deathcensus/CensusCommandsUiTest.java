package com.pengin0503.deathcensus;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.pengin0503.deathcensus.ui.ChatUiRequestParser;
import com.pengin0503.deathcensus.ui.UiRequest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hidden Brigadier subtree must parse into the same UiRequest as the parser. */
class CensusCommandsUiTest {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static UiRequest parsed(String input) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(CensusCommands.root());
        ParseResults<CommandSourceStack> result = dispatcher.parse(input, null);
        assertTrue(result.getExceptions().isEmpty(), "parse failed: " + result.getExceptions());
        assertFalse(result.getContext().getNodes().isEmpty());
        String path = result.getContext().getNodes().stream()
                .map(node -> node.getNode().getName())
                .reduce((a, b) -> a + ":" + b).orElse("");
        assertTrue(path.contains("ui"), "expected the hidden ui subtree, got " + path);
        return ChatUiRequestParser.parse(VIEWER, CensusCommands.uiArgs(input)).orElseThrow();
    }

    @Test
    void everyUiNodeProducesTheDocumentedAction() {
        String uuid = TARGET.toString();
        String token = "11111111-2222-3333-4444-555555555555";
        assertEquals("home", parsed("deathcensus ui home").action());
        assertEquals("history", parsed("deathcensus ui history").action());
        UiRequest page = parsed("deathcensus ui history 2 " + token);
        assertEquals(2, page.page());
        assertEquals("history_search", parsed("deathcensus ui history filter 7d fall Steve fell").action());
        assertEquals("detail", parsed("deathcensus ui detail " + uuid + " 42 " + token).action());
        assertEquals("respect", parsed("deathcensus ui respect " + uuid + " 42 " + token).action());
        assertEquals("delete_confirm",
                parsed("deathcensus ui delete " + uuid + " 42 confirm " + token).action());
        assertEquals("excuse_open", parsed("deathcensus ui excuse " + uuid + " 42 " + token).action());
        assertEquals("ranking_page", parsed("deathcensus ui rankings page 3 " + token).action());
        assertEquals("rankings", parsed("deathcensus ui rankings survival all 2 " + token).action());
        assertEquals("privacy_save", parsed("deathcensus ui privacy optin " + token).action());
        assertEquals("admin", parsed("deathcensus ui admin").action());
    }

    private static boolean containsKey(Component component, String key) {
        if (component.getContents() instanceof TranslatableContents contents
                && key.equals(contents.getKey())) {
            return true;
        }
        for (Component sibling : component.getSiblings()) {
            if (containsKey(sibling, key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * S7: the chat UI's prev/next buttons emit {@code /dc ui history <page> <token>}.
     * Parsing alone never caught the missing token child — Brigadier rejected the
     * very same command at execute time. Run the real dispatcher here and prove
     * execution reaches the UI request handler.
     */
    @Test
    void historyPageTokenCommandExecutesThroughUiRequestPath() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(CensusCommands.root());
        // Same /dc alias registration as onRegisterCommands, so the executed
        // command line is exactly the one ChatUi's prev/next buttons emit.
        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("dc")
                .redirect(dispatcher.getRoot().getChild("deathcensus")));

        List<Component> messages = new ArrayList<>();
        CommandSource fakeSource = new CommandSource() {
            @Override
            public void sendSystemMessage(Component component) {
                messages.add(component);
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        // A non-player source makes runUi take its players-only branch; the
        // single failure message is our observable that the command ran.
        CommandSourceStack source = new CommandSourceStack(fakeSource, null, null, null, 0,
                "tester", Component.empty(), null, null);
        String token = "11111111-2222-3333-4444-555555555555";
        // Brigadier command lines omit the leading slash used in click hints.
        String command = "dc ui history 2 " + token;

        int result = assertDoesNotThrow(() -> dispatcher.execute(command, source),
                "the token child under history page must be registered for execute");
        assertEquals(0, result, "non-player sources are refused by the UI handler");
        assertEquals(1, messages.size(), "runUi must have been executed");
        assertTrue(containsKey(messages.get(0), "command.deathcensus.players_only"),
                "unexpected feedback from runUi: " + messages.get(0));

        // The executed command line is the exact one the UI handler would parse.
        UiRequest request = ChatUiRequestParser.parse(VIEWER, CensusCommands.uiArgs(command)).orElseThrow();
        assertEquals("history", request.action());
        assertEquals(2, request.page());
        assertEquals(token, request.token());
    }

    @Test
    void existingSubcommandsStillParseAfterAddingUiTree() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(CensusCommands.root());
        for (String command : List.of(
                "deathcensus help",
                "deathcensus excuse just because",
                "deathcensus excuse " + TARGET + " 42 for that",
                "deathcensus history 2 fall",
                "deathcensus stats server 7d",
                "deathcensus leaderboard deaths 7d 2",
                "deathcensus optout",
                "deathcensus witnesses",
                "deathcensus witnesses 2",
                "deathcensus witnesses Steve 7d 2",
                "deathcensus witnesses 7d 2")) {
            ParseResults<CommandSourceStack> result = dispatcher.parse(command, null);
            assertTrue(result.getExceptions().isEmpty(), command + " -> " + result.getExceptions());
            assertFalse(result.getReader().canRead(), command + " was not fully consumed");
        }
    }

    @Test
    void exportFlagHasNodesBeforeAndAfterTheOptionalPlayer() throws IOException {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(CensusCommands.root());
        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild("deathcensus");
        assertNotNull(root);
        CommandNode<CommandSourceStack> export = root.getChild("export");
        assertNotNull(export);
        // Export remains gated through PermissionBridge's EXPORT node.
        String sourceDir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(sourceDir);
        String source = Files.readString(Path.of(sourceDir,
                "com", "pengin0503", "deathcensus", "CensusCommands.java"));
        String exportTree = source.substring(source.indexOf("literal(\"export\")"),
                source.indexOf("root.then(export)"));
        assertTrue(exportTree.contains("PermissionBridge.requires(DeathCensusPermission.EXPORT"));
        for (String format : List.of("html", "json", "csv")) {
            CommandNode<CommandSourceStack> formatNode = export.getChild(format);
            assertNotNull(formatNode, format);
            assertNotNull(formatNode.getChild("--anonymize"), format + " flag without player");
            CommandNode<CommandSourceStack> player = formatNode.getChild("player");
            assertNotNull(player, format + " player argument");
            assertNotNull(player.getChild("--anonymize"), format + " flag after player");
            assertNotNull(formatNode.getChild("--anonymize").getChild("player"),
                    format + " player after flag");
        }
    }
}
