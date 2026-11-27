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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PR-8b: /dc privacy syntax, PRIVACY_OTHER gate placement and lang coverage. */
class PrivacyCommandsTest {
    private static String source() throws IOException {
        String dir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(dir);
        return Files.readString(Path.of(dir, "com", "pengin0503", "deathcensus", "CensusCommands.java"));
    }

    @Test
    void selfFlagsAndAdminOtherFormsAreRegistered() throws IOException {
        String source = source();
        assertTrue(source.contains("LiteralArgumentBuilder<CommandSourceStack> privacy = literal(\"privacy\")"));
        assertTrue(source.contains(".executes(ctx -> privacy(ctx, null))"));
        assertTrue(source.contains("for (PlayerPrivacy.Flag flag : PlayerPrivacy.Flag.values())"));
        assertTrue(source.contains("literal(flag.token())"));
        assertTrue(source.contains("privacy.then(literal(\"optout\")"));
        assertTrue(source.contains("privacy.then(literal(\"optin\")"));
        assertTrue(source.contains("argument(\"player\", StringArgumentType.word())"));
        assertTrue(source.contains("argument(\"flag\", StringArgumentType.word())"));
        assertTrue(source.contains("argument(\"value\", StringArgumentType.word())"));
        assertTrue(source.contains("privacy(ctx, StringArgumentType.getString(ctx, \"player\"))"));
    }

    @Test
    void otherTargetIsGatedByPrivacyOtherNotBySelfGate() throws IOException {
        String source = source();
        int start = source.lastIndexOf("LiteralArgumentBuilder<CommandSourceStack> privacy = literal(\"privacy\")");
        int end = source.indexOf("root.then(privacy);", start);
        assertTrue(start >= 0 && end > start, "privacy subtree not found");
        String tree = source.substring(start, end);
        assertTrue(tree.contains("DeathCensusPermission.PRIVACY_OTHER"),
                "other-player branch must route through the PRIVACY_OTHER bridge");
        assertTrue(source.contains("PlayerPrivacy.canAccess"),
                "target permission must be re-checked at execution time");
        assertTrue(source.contains("pc.hideCoordinates = state"));
        assertTrue(source.contains("pc.hidePvpOpponent = state"));
        assertTrue(source.contains("pc.discordOptOut = state"));
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
