package com.pengin0503.deathcensus;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PR-8b: /dc privacy syntax, PRIVACY_OTHER gate placement and lang coverage. */
class PrivacyCommandsTest {
    @SuppressWarnings("unchecked")
    private static CommandDispatcher<CommandSourceStack> dispatcher() throws Exception {
        Method root = CensusCommands.class.getDeclaredMethod("root");
        root.setAccessible(true);
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register((LiteralArgumentBuilder<CommandSourceStack>) root.invoke(null));
        return dispatcher;
    }

    private static void parses(String input) throws Exception {
        ParseResults<CommandSourceStack> parsed = dispatcher().parse(input, null);
        assertTrue(parsed.getExceptions().isEmpty(), input + " -> " + parsed.getExceptions());
        assertFalse(parsed.getReader().canRead(), input + " was not fully consumed");
    }

    @Test
    void selfFlagsAndAdminOtherFormsParse() throws Exception {
        for (String command : List.of(
                "deathcensus privacy",
                "deathcensus privacy full on",
                "deathcensus privacy full off",
                "deathcensus privacy coords on",
                "deathcensus privacy coords off",
                "deathcensus privacy opponent on",
                "deathcensus privacy discord off",
                "deathcensus privacy optout",
                "deathcensus privacy optin")) {
            parses(command);
        }
    }

    @Test
    void otherTargetIsGatedByPrivacyOtherNotBySelfGate() throws IOException {
        String sourceDir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(sourceDir);
        String source = Files.readString(Path.of(sourceDir,
                "com", "pengin0503", "deathcensus", "CensusCommands.java"));
        int start = source.lastIndexOf("LiteralArgumentBuilder<CommandSourceStack> privacy = literal(\"privacy\")");
        int end = source.indexOf("root.then(privacy);", start);
        assertTrue(start >= 0 && end > start, "privacy subtree not found");
        String tree = source.substring(start, end);
        assertTrue(tree.contains("DeathCensusPermission.PRIVACY_OTHER"),
                "other-player branch must route through the PRIVACY_OTHER bridge");
        assertTrue(tree.contains("for (PlayerPrivacy.Flag flag : PlayerPrivacy.Flag.values())"),
                "all four self-service flags must be registered");
        assertTrue(tree.contains("arg(\"flag\"") || tree.contains("argument(\"flag\""),
                "admin branch must accept a flag token");
        assertTrue(source.contains("PlayerPrivacy.canAccess"),
                "target permission must be re-checked at execution time");
        assertTrue(source.contains("pc.hideCoordinates = state"));
        assertTrue(source.contains("pc.hidePvpOpponent = state"));
        assertTrue(source.contains("pc.discordOptOut = state"));
        // The legacy full opt-out remains a plain player-only command.
        assertTrue(source.contains("root.then(literal(\"optout\").executes(ctx -> setOptOut(ctx, true)))"));
        assertTrue(source.contains("root.then(literal(\"optin\").executes(ctx -> setOptOut(ctx, false)))"));
    }

    @Test
    void bothLanguagesCarryThePrivacyKeys() throws IOException {
        String[] keys = {
                "command.deathcensus.help.privacy",
                "command.deathcensus.privacy.view",
                "command.deathcensus.privacy.coords.on",
                "command.deathcensus.privacy.opponent.off",
                "command.deathcensus.privacy.discord.on",
                "command.deathcensus.ui.detail.coords_hidden",
                "command.deathcensus.ui.input.hide_coords",
                "command.deathcensus.ui.input.hide_opponent",
                "command.deathcensus.ui.input.discord_optout"
        };
        for (String lang : List.of("en_us", "ja_jp")) {
            try (InputStream in = PrivacyCommandsTest.class.getResourceAsStream(
                    "/assets/deathcensus/lang/" + lang + ".json")) {
                assertNotNull(in, lang);
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    JsonObject table = JsonParser.parseReader(reader).getAsJsonObject();
                    for (String key : keys) {
                        assertTrue(table.has(key), lang + " missing " + key);
                    }
                }
            }
        }
        assertEquals(212, loadCount("en_us"));
        assertEquals(loadCount("en_us"), loadCount("ja_jp"));
    }

    private static int loadCount(String lang) throws IOException {
        try (InputStream in = PrivacyCommandsTest.class.getResourceAsStream(
                "/assets/deathcensus/lang/" + lang + ".json")) {
            assertNotNull(in);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject().size();
            }
        }
    }
}
