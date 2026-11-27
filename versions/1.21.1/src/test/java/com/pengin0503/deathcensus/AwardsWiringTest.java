package com.pengin0503.deathcensus;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pengin0503.deathcensus.ui.ChatUiContext;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-tree compile/wiring check for the 1.4.0 PR-4 award and title surface (§2). */
class AwardsWiringTest {
    private static String source(String file) throws IOException {
        String dir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(dir, "deathcensus.mainSourceDir system property is not set");
        return Files.readString(Path.of(dir, "com", "pengin0503", "deathcensus", file));
    }

    private static JsonObject loadLang(String lang) throws IOException {
        String path = "/assets/deathcensus/lang/" + lang + ".json";
        try (InputStream in = AwardsWiringTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing lang resource " + path);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    @Test
    void configDefaultsMatchTheRecommendedValues() {
        assertTrue(Config.awardsEnabled());
        assertEquals("all", Config.awardsDefaultPeriod());
        assertTrue(Config.awardsInDigest());
        assertEquals(1, Config.awardsMinCount());
        for (String period : List.of("today", "7d", "30d", "all")) {
            assertTrue(Config.validateAwardsPeriod(period), period);
        }
        assertFalse(Config.validateAwardsPeriod("year"));
        assertFalse(Config.validateAwardsPeriod(""));
    }

    @Test
    void configDeclaresAllFourKeysWithTheSpecifiedClamps() throws IOException {
        String config = source("Config.java");
        assertTrue(config.contains("define(\"awardsEnabled\", true)"), config);
        assertTrue(config.contains("define(\"awardsDefaultPeriod\", \"all\", Config::validateAwardsPeriod)"), config);
        assertTrue(config.contains("define(\"awardsInDigest\", true)"), config);
        assertTrue(config.contains("defineInRange(\"awardsMinCount\", 1, 1, 100)"), config);
    }

    @Test
    void commandTreeExposesEveryAwardArgumentShape() throws IOException {
        String commands = source("CensusCommands.java");
        assertTrue(commands.contains("literal(\"awards\")"), commands);
        assertTrue(commands.contains("Config.awardsDefaultPeriod()"), commands);
        assertTrue(commands.contains("for (String period : PERIODS)"), commands);
        assertTrue(commands.contains("awards.then(literal(period)"), commands);
        assertTrue(commands.contains("argument(\"page\", IntegerArgumentType.integer(1))"), commands);
        assertTrue(commands.contains("command.deathcensus.awards.header"), commands);
        assertTrue(commands.contains("command.deathcensus.awards.empty"), commands);
        assertTrue(commands.contains("command.deathcensus.awards.retained"), commands);
        assertTrue(commands.contains("command.deathcensus.awards.help"), commands);
    }

    @Test
    void rarestCauseIsPeriodScopedAndEvaluationHasAPureSeam() throws IOException {
        String commands = source("CensusCommands.java");
        assertTrue(commands.contains("static List<AwardResult> computeAwardsFrom("), commands);
        assertTrue(commands.contains("CensusStats.rarestCause(fullPeriodSnapshot, true)"), commands);
        assertFalse(commands.contains("CensusStats.rarestCause(data.snapshot(), true)"), commands);
        assertTrue(commands.contains("computeAwards(server, publicPlayers, inPeriod, false)"), commands);
    }

    @Test
    void uiAdapterHonorsConfiguredAwardsMinCount() throws IOException {
        String dir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(dir);
        String file = Files.exists(Path.of(dir, "com", "pengin0503", "deathcensus",
                "ui", "DialogUiContext.java")) ? "ui/DialogUiContext.java" : "ui/ChatUiContext.java";
        String src = source(file);
        assertTrue(src.contains("public int awardsMinCount()"), src);
        assertTrue(src.contains("return Config.awardsMinCount();"), src);
        assertEquals(Config.awardsMinCount(), new ChatUiContext(null).awardsMinCount());
    }

    @Test
    void namedLinesUseAllNineRuleTitlesAndTheSharedRowTemplate() throws IOException {
        String commands = source("CensusCommands.java");
        for (String rule : List.of("most_deaths", "longest_survival", "most_falls", "most_lava",
                "most_lightning", "most_respected", "category_complete", "rarest_cause",
                "witness_famous")) {
            assertTrue(commands.contains("command.deathcensus.awards.title." + rule), rule);
        }
        assertTrue(commands.contains("command.deathcensus.awards.row"), commands);
        assertTrue(commands.contains("Awards.evaluate(publicSnapshot, minCount,"), commands);
        assertTrue(commands.contains("computeAwardsFrom(eligible, fullPeriodSnapshot, categories,"), commands);
        assertTrue(commands.contains("ServerLang.formatForLang(language,"), commands);
    }

    @Test
    void everySurfaceIsGatedAndPrivacyAware() throws IOException {
        String commands = source("CensusCommands.java");
        assertTrue(commands.contains("if (server == null || !Config.awardsEnabled())"), commands);
        assertTrue(commands.contains("player.discordOptOut()"), commands);
        assertTrue(commands.contains("!entry.getValue().optOutPublic"), commands);

        String export = source("CensusExport.java");
        assertTrue(export.contains("CensusCommands.computeAwards(server, players, allPlayers, false)"), export);
        assertTrue(export.contains("CensusCommands.computeAwards(server, players, inPeriod, false)"), export);
        assertTrue(export.contains("CensusCommands.awardLines("), export);
        assertTrue(export.contains("command.deathcensus.export.awards"), export);

        String discord = source("DiscordNotifier.java");
        assertTrue(discord.contains("Config.awardsInDigest()"), discord);
        assertTrue(discord.contains("CensusCommands.computeAwards(server, publicRecent, fullRecent, true)"), discord);
        assertTrue(discord.contains("discord.deathcensus.awards.section"), discord);

        String dir = System.getProperty("deathcensus.mainSourceDir");
        String uiFile = Files.exists(Path.of(dir, "com", "pengin0503", "deathcensus",
                "ui", "DialogUiContext.java")) ? "ui/DialogUiContext.java" : "ui/ChatUiContext.java";
        String ui = source(uiFile);
        assertTrue(ui.contains("public boolean awardsEnabled()"), ui);
        assertTrue(ui.contains("public Map<String, Set<String>> lifetimeCategories()"), ui);
        assertTrue(ui.contains("public String label(String key)"), ui);

        String adapter = source(uiFile.contains("Dialog")
                ? "ui/DialogUi.java" : "ui/ChatUi.java");
        assertTrue(adapter.contains("Config.awardsEnabled()"), adapter);
        assertTrue(adapter.contains("command.deathcensus.ui.ranking.awards"), adapter);
        if (uiFile.contains("Chat")) {
            assertTrue(adapter.contains("command.deathcensus.ui.chat.ranking_hint_no_awards"), adapter);
        }
    }

    @Test
    void bothLangBundlesCarryEveryAwardKey() throws IOException {
        JsonObject en = loadLang("en_us");
        JsonObject ja = loadLang("ja_jp");
        List<String> keys = List.of(
                "command.deathcensus.awards.help", "command.deathcensus.awards.header",
                "command.deathcensus.awards.empty", "command.deathcensus.awards.row",
                "command.deathcensus.awards.retained", "command.deathcensus.ui.ranking.awards",
                "command.deathcensus.ui.chat.ranking_hint_no_awards",
                "command.deathcensus.export.awards", "discord.deathcensus.awards.section",
                "command.deathcensus.awards.title.most_deaths",
                "command.deathcensus.awards.title.longest_survival",
                "command.deathcensus.awards.title.most_falls",
                "command.deathcensus.awards.title.most_lava",
                "command.deathcensus.awards.title.most_lightning",
                "command.deathcensus.awards.title.most_respected",
                "command.deathcensus.awards.title.category_complete",
                "command.deathcensus.awards.title.rarest_cause",
                "command.deathcensus.awards.title.witness_famous");
        assertEquals(keys.size(), 18);
        for (String key : keys) {
            assertTrue(en.has(key), "missing en_us entry for " + key);
            assertTrue(ja.has(key), "missing ja_jp entry for " + key);
            assertFalse(en.get(key).getAsString().isBlank(), "blank en_us entry for " + key);
            assertFalse(ja.get(key).getAsString().isBlank(), "blank ja_jp entry for " + key);
        }
    }

    @Test
    void commonRuleTokensAreStable() {
        assertEquals(Awards.Rule.MOST_DEATHS, Awards.Rule.fromToken("most_deaths"));
        assertEquals(Awards.Rule.CATEGORY_COMPLETE, Awards.Rule.fromToken("category_complete"));
        assertEquals(Awards.Rule.WITNESS_FAMOUS, Awards.Rule.fromToken("witness_famous"));
        assertNull(Awards.Rule.fromToken("unknown"));
        assertTrue(Awards.Context.EMPTY.categories("unknown-uuid").isEmpty());
    }
}
