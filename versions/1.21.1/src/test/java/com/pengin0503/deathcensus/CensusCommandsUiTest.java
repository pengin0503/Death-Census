package com.pengin0503.deathcensus;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.pengin0503.deathcensus.ui.ChatUiRequestParser;
import com.pengin0503.deathcensus.ui.UiRequest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                "deathcensus optout")) {
            ParseResults<CommandSourceStack> result = dispatcher.parse(command, null);
            assertTrue(result.getExceptions().isEmpty(), command + " -> " + result.getExceptions());
            assertFalse(result.getReader().canRead(), command + " was not fully consumed");
        }
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
     * same line at execute time. Run the real dispatcher and prove execution
     * reaches the UI request handler.
     */
    @Test
    void historyPageTokenCommandExecutesThroughUiRequestPath() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(CensusCommands.root());
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
                "the token child under the history page node must be registered for execute");
        assertEquals(0, result, "non-player sources are refused by the UI handler");
        assertEquals(1, messages.size(), "runUi must have been executed");
        assertTrue(containsKey(messages.get(0), "command.deathcensus.players_only"),
                "unexpected feedback from runUi: " + messages.get(0));

        UiRequest request = ChatUiRequestParser.parse(VIEWER, CensusCommands.uiArgs(command)).orElseThrow();
        assertEquals("history", request.action());
        assertEquals(2, request.page());
        assertEquals(token, request.token());
    }

    @Test
    void publicHistoryLineDoesNotNameAHiddenOpponent() {
        DeathEntry raw = new DeathEntry(VIEWER.toString(), "Alice", 1000L, 1L, 500, 400,
                "minecraft:overworld", 1.0, 64.0, 2.0, "player", "minecraft:player",
                "Alice was slain by Bob", "minecraft:player", "Bob", "survival", 5,
                "why Bob", "gg Bob", 0);
        HiddenIdentities hidden = HiddenIdentities.of(List.of(
                new PlayerDeaths(TARGET.toString(), "Bob", true, List.of())));

        String publicLine = CensusCommands.formatEntryLine(raw, 1, hidden);
        assertFalse(publicLine.contains("Bob"), publicLine);
        assertTrue(publicLine.contains("Anonymous"), publicLine);
        // Self/admin viewers are passed an empty dictionary and keep the truth.
        assertTrue(CensusCommands.formatEntryLine(raw, 1, HiddenIdentities.empty()).contains("Bob"));
    }
}
