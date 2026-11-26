package com.pengin0503.deathcensus;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.pengin0503.deathcensus.ui.ChatUiRequestParser;
import com.pengin0503.deathcensus.ui.UiRequest;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

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
}
