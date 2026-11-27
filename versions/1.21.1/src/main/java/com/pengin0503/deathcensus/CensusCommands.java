package com.pengin0503.deathcensus;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.pengin0503.deathcensus.AwardResult;
import com.pengin0503.deathcensus.Awards;
import com.pengin0503.deathcensus.DiscordLogic.SeasonAwards;
import com.pengin0503.deathcensus.DiscordLogic.SeasonLabels;
import com.pengin0503.deathcensus.DiscordLogic.SeasonSection;
import com.pengin0503.deathcensus.ChronicleEvent;
import com.pengin0503.deathcensus.ChronicleRules;
import com.pengin0503.deathcensus.DeathRedactor;
import com.pengin0503.deathcensus.HiddenIdentities;
import com.pengin0503.deathcensus.PlayerPrivacy;
import com.pengin0503.deathcensus.WitnessAnalysis;
import com.pengin0503.deathcensus.WitnessRedactor;
import com.pengin0503.deathcensus.ui.ChatUi;
import com.pengin0503.deathcensus.ui.UiActions;
import com.pengin0503.deathcensus.ui.UiPrivacy;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * /deathcensus (alias /dc) — personal history, server statistics,
 * leaderboards, privacy opt-out and operator tools. Chat output only.
 */
public final class CensusCommands {
    private static final int PAGE_SIZE = 10;
    private static final List<String> PERIODS = List.of("today", "7d", "30d", "all");
    private static final long DAY_MILLIS = 86_400_000L;

    private CensusCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(root());
        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("dc")
                .executes(CensusCommands::entry)
                .redirect(dispatcher.getRoot().getChild("deathcensus")));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static <T> RequiredArgumentBuilder<CommandSourceStack, T> arg(String name, ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    /**
     * Hidden {@code /dc ui ...} subtree: it is absent from our own help output,
     * and every node just forwards its raw arguments to the common UiRequest
     * parser. Permissions and records are re-checked by UiActions at execution.
     */
    static LiteralArgumentBuilder<CommandSourceStack> uiTree() {
        LiteralArgumentBuilder<CommandSourceStack> ui = literal("ui");

        ui.then(literal("home").executes(CensusCommands::runUi));

        LiteralArgumentBuilder<CommandSourceStack> history = literal("history").executes(CensusCommands::runUi);
        history.then(arg("page", IntegerArgumentType.integer(1)).executes(CensusCommands::runUi)
                .then(arg("token", StringArgumentType.word()).executes(CensusCommands::runUi)));
        history.then(literal("filter")
                .then(arg("period", StringArgumentType.word()).executes(CensusCommands::runUi)
                        .then(arg("category", StringArgumentType.word()).executes(CensusCommands::runUi)
                                .then(arg("query", StringArgumentType.greedyString())
                                        .executes(CensusCommands::runUi)))));
        ui.then(history);

        for (String action : List.of("detail", "respect")) {
            ui.then(literal(action).executes(CensusCommands::runUi)
                    .then(arg("uuid", StringArgumentType.word()).executes(CensusCommands::runUi)
                            .then(arg("epoch", LongArgumentType.longArg(0)).executes(CensusCommands::runUi)
                                    .then(arg("token", StringArgumentType.word())
                                            .executes(CensusCommands::runUi)))));
        }
        LiteralArgumentBuilder<CommandSourceStack> excuse = literal("excuse").executes(CensusCommands::runUi)
                .then(arg("uuid", StringArgumentType.word()).executes(CensusCommands::runUi)
                        .then(arg("epoch", LongArgumentType.longArg(0)).executes(CensusCommands::runUi)
                                .then(arg("token", StringArgumentType.word()).executes(CensusCommands::runUi)
                                        .then(literal("save")
                                                .then(arg("text", StringArgumentType.greedyString())
                                                        .executes(CensusCommands::runUi))))
                                .then(literal("save")
                                        .then(arg("text", StringArgumentType.greedyString())
                                                .executes(CensusCommands::runUi)))));
        ui.then(excuse);

        ui.then(literal("delete").executes(CensusCommands::runUi)
                .then(arg("uuid", StringArgumentType.word()).executes(CensusCommands::runUi)
                        .then(arg("epoch", LongArgumentType.longArg(0)).executes(CensusCommands::runUi)
                                .then(arg("token", StringArgumentType.word()).executes(CensusCommands::runUi))
                                .then(literal("confirm").executes(CensusCommands::runUi)
                                        .then(arg("token", StringArgumentType.word())
                                                .executes(CensusCommands::runUi))))));

        LiteralArgumentBuilder<CommandSourceStack> rankings = literal("rankings").executes(CensusCommands::runUi);
        rankings.then(literal("page")
                .then(arg("page", IntegerArgumentType.integer(1)).executes(CensusCommands::runUi)
                        .then(arg("token", StringArgumentType.word()).executes(CensusCommands::runUi))));
        rankings.then(arg("type", StringArgumentType.word()).executes(CensusCommands::runUi)
                .then(arg("period", StringArgumentType.word()).executes(CensusCommands::runUi)
                        .then(arg("page", IntegerArgumentType.integer(1)).executes(CensusCommands::runUi)
                                .then(arg("token", StringArgumentType.word()).executes(CensusCommands::runUi)))));
        ui.then(rankings);

        LiteralArgumentBuilder<CommandSourceStack> privacy = literal("privacy").executes(CensusCommands::runUi);
        for (String toggle : List.of("optout", "optin")) {
            privacy.then(literal(toggle).executes(CensusCommands::runUi)
                    .then(arg("token", StringArgumentType.word()).executes(CensusCommands::runUi)));
        }
        // §7: the chat GUI toggles all four fields through the same input keys.
        for (String toggle : List.of("full", "coords", "opponent", "discord")) {
            privacy.then(literal(toggle).executes(CensusCommands::runUi)
                    .then(arg("value", StringArgumentType.word()).executes(CensusCommands::runUi)
                            .then(arg("token", StringArgumentType.word()).executes(CensusCommands::runUi))));
        }
        ui.then(privacy);

        ui.then(literal("admin").executes(CensusCommands::runUi));
        return ui;
    }

    /** Raw tokens handed to the common UiRequest parser by every ui-tree node. */
    static List<String> uiArgs(String input) {
        List<String> args = new ArrayList<>();
        for (String part : input == null ? new String[0] : input.trim().split("\\s+")) {
            if (!part.isEmpty()) {
                args.add(part);
            }
        }
        return args;
    }

    private static int runUi(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer self = ctx.getSource().getPlayer();
        if (self == null) {
            fail(ctx, "command.deathcensus.players_only");
            return 0;
        }
        return ChatUi.dispatchArgs(self, uiArgs(ctx.getInput())) ? 1 : 0;
    }

    static LiteralArgumentBuilder<CommandSourceStack> root() {
        LiteralArgumentBuilder<CommandSourceStack> root = literal("deathcensus")
                .executes(CensusCommands::entry);

        root.then(literal("help").executes(CensusCommands::help));
        root.then(uiTree());

        // Existing /dc excuse <text> keeps its latest-death semantics; the
        // selected-death form is an additive branch for the UI excuse flow.
        root.then(literal("excuse")
                .then(arg("uuid", StringArgumentType.word())
                        .then(arg("epoch", LongArgumentType.longArg(0))
                                .then(arg("text", StringArgumentType.greedyString())
                                        .executes(ctx -> excuseSelected(ctx,
                                                StringArgumentType.getString(ctx, "uuid"),
                                                LongArgumentType.getLong(ctx, "epoch"),
                                                StringArgumentType.getString(ctx, "text"))))))
                .then(arg("text", StringArgumentType.greedyString())
                        .executes(CensusCommands::excuse)));

        root.then(literal("history")
                .executes(ctx -> history(ctx, null, 1))
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, Integer>argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> history(ctx, null, IntegerArgumentType.getInteger(ctx, "page")))
                        .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                                .<CommandSourceStack, String>argument("filter", StringArgumentType.greedyString())
                                .suggests((ctx, builder) ->
                                        SharedSuggestionProvider.suggest(filterSuggestions(ctx), builder))
                                .executes(ctx -> history(ctx, null,
                                        IntegerArgumentType.getInteger(ctx, "page"),
                                        StringArgumentType.getString(ctx, "filter")))))
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, String>argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(playerNames(ctx), builder))
                        .executes(ctx -> history(ctx, StringArgumentType.getString(ctx, "player"), 1))
                        .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                                .<CommandSourceStack, Integer>argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> history(ctx, StringArgumentType.getString(ctx, "player"),
                                        IntegerArgumentType.getInteger(ctx, "page")))
                                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                                        .<CommandSourceStack, String>argument("filter", StringArgumentType.greedyString())
                                        .suggests((ctx, builder) ->
                                                SharedSuggestionProvider.suggest(filterSuggestions(ctx), builder))
                                        .executes(ctx -> history(ctx, StringArgumentType.getString(ctx, "player"),
                                                IntegerArgumentType.getInteger(ctx, "page"),
                                                StringArgumentType.getString(ctx, "filter")))))
                        .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                                .<CommandSourceStack, String>argument("filter", StringArgumentType.greedyString())
                                .suggests((ctx, builder) ->
                                        SharedSuggestionProvider.suggest(filterSuggestions(ctx), builder))
                                .executes(ctx -> history(ctx, StringArgumentType.getString(ctx, "player"), 1,
                                        StringArgumentType.getString(ctx, "filter"))))));

        // 1.4.0 PR-2: /dc witnesses [player] [today|7d|30d|all] [page]
        LiteralArgumentBuilder<CommandSourceStack> witnesses = literal("witnesses")
                .executes(ctx -> witnesses(ctx, null, "all", 1))
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, Integer>argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> witnesses(ctx, null, "all",
                                IntegerArgumentType.getInteger(ctx, "page"))));
        com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> witnessPlayer =
                com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, String>argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(playerNames(ctx), builder))
                        .executes(ctx -> witnesses(ctx,
                                StringArgumentType.getString(ctx, "player"), "all", 1))
                        .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                                .<CommandSourceStack, Integer>argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> witnesses(ctx, StringArgumentType.getString(ctx, "player"),
                                        "all", IntegerArgumentType.getInteger(ctx, "page"))));
        for (String period : PERIODS) {
            witnessPlayer.then(literal(period)
                    .executes(ctx -> witnesses(ctx, StringArgumentType.getString(ctx, "player"), period, 1))
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                            .<CommandSourceStack, Integer>argument("page", IntegerArgumentType.integer(1))
                            .executes(ctx -> witnesses(ctx, StringArgumentType.getString(ctx, "player"), period,
                                    IntegerArgumentType.getInteger(ctx, "page")))));
            witnesses.then(literal(period)
                    .executes(ctx -> witnesses(ctx, null, period, 1))
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                            .<CommandSourceStack, Integer>argument("page", IntegerArgumentType.integer(1))
                            .executes(ctx -> witnesses(ctx, null, period,
                                    IntegerArgumentType.getInteger(ctx, "page")))));
        }
        witnesses.then(witnessPlayer);
        root.then(witnesses);

        // 1.4.0 PR-4: /dc awards [today|7d|30d|all] [page] (§2), hidden when disabled.
        if (Config.awardsEnabled()) {
            LiteralArgumentBuilder<CommandSourceStack> awards = literal("awards")
                    .executes(ctx -> awards(ctx, Config.awardsDefaultPeriod(), 1))
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                            .<CommandSourceStack, Integer>argument("page", IntegerArgumentType.integer(1))
                            .executes(ctx -> awards(ctx, Config.awardsDefaultPeriod(),
                                    IntegerArgumentType.getInteger(ctx, "page"))));
            for (String period : PERIODS) {
                awards.then(literal(period)
                        .executes(ctx -> awards(ctx, period, 1))
                        .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                                .<CommandSourceStack, Integer>argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> awards(ctx, period,
                                        IntegerArgumentType.getInteger(ctx, "page")))));
            }
            root.then(awards);
        }

        // 1.4.0 PR-5: /dc chronicle [oldest|newest] [page] (§3), public and paginated.
        if (Config.chronicleEnabled()) {
            LiteralArgumentBuilder<CommandSourceStack> chronicle = literal("chronicle")
                    .executes(ctx -> chronicle(ctx, "oldest", 1))
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                            .<CommandSourceStack, Integer>argument("page", IntegerArgumentType.integer(1))
                            .executes(ctx -> chronicle(ctx, "oldest",
                                    IntegerArgumentType.getInteger(ctx, "page"))));
            for (String order : List.of("oldest", "newest")) {
                chronicle.then(literal(order)
                        .executes(ctx -> chronicle(ctx, order, 1))
                        .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                                .<CommandSourceStack, Integer>argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> chronicle(ctx, order,
                                        IntegerArgumentType.getInteger(ctx, "page")))));
            }
            root.then(chronicle);
        }

        LiteralArgumentBuilder<CommandSourceStack> statsServer = literal("server")
                .executes(CensusCommands::serverStats);
        for (String period : PERIODS) {
            statsServer.then(literal(period).executes(ctx -> serverStats(ctx, period)));
        }
        root.then(literal("stats")
                .executes(CensusCommands::ownStats)
                .then(statsServer));

        LiteralArgumentBuilder<CommandSourceStack> leaderboard = literal("leaderboard");
        for (String type : List.of("deaths", "survival", "fastest", "rarest")) {
            LiteralArgumentBuilder<CommandSourceStack> typeNode = literal(type)
                    .executes(ctx -> leaderboard(ctx, type, 1))
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                            .<CommandSourceStack, Integer>argument("page", IntegerArgumentType.integer(1))
                            .executes(ctx -> leaderboard(ctx, type, IntegerArgumentType.getInteger(ctx, "page"))));
            for (String period : PERIODS) {
                typeNode.then(literal(period)
                        .executes(ctx -> leaderboard(ctx, type, period, 1))
                        .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                                .<CommandSourceStack, Integer>argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> leaderboard(ctx, type, period,
                                        IntegerArgumentType.getInteger(ctx, "page")))));
            }
            leaderboard.then(typeNode);
        }
        root.then(leaderboard);

        root.then(literal("optout").executes(ctx -> setOptOut(ctx, true)));
        root.then(literal("optin").executes(ctx -> setOptOut(ctx, false)));

        // PR-8b: self-service flags need no node; reading/changing someone else
        // is gated by PRIVACY_OTHER through the PR-1 bridge (OP 2 fallback).
        LiteralArgumentBuilder<CommandSourceStack> privacy = literal("privacy")
                .executes(ctx -> privacy(ctx, null));
        for (PlayerPrivacy.Flag flag : PlayerPrivacy.Flag.values()) {
            privacy.then(literal(flag.token())
                    .then(arg("value", StringArgumentType.word())
                            .executes(ctx -> setPrivacy(ctx, null, flag.token(),
                                    StringArgumentType.getString(ctx, "value")))));
        }
        privacy.then(literal("optout").executes(ctx -> setPrivacy(ctx, null, "full", "on")));
        privacy.then(literal("optin").executes(ctx -> setPrivacy(ctx, null, "full", "off")));
        privacy.then(arg("player", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(playerNames(ctx), builder))
                .requires(PermissionBridge.requires(DeathCensusPermission.PRIVACY_OTHER,
                        src -> src.hasPermission(2)))
                .executes(ctx -> privacy(ctx, StringArgumentType.getString(ctx, "player")))
                .then(arg("flag", StringArgumentType.word())
                        .then(arg("value", StringArgumentType.word())
                                .executes(ctx -> setPrivacy(ctx,
                                        StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "flag"),
                                        StringArgumentType.getString(ctx, "value"))))));
        root.then(privacy);

        root.then(literal("lookup")
                .requires(PermissionBridge.requires(DeathCensusPermission.LOOKUP,
                        src -> src.hasPermission(2)))
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, String>argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(playerNames(ctx), builder))
                        .executes(ctx -> lookup(ctx, StringArgumentType.getString(ctx, "player"), 1))
                        .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                                .<CommandSourceStack, Integer>argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> lookup(ctx, StringArgumentType.getString(ctx, "player"),
                                        IntegerArgumentType.getInteger(ctx, "page")))
                                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                                        .<CommandSourceStack, String>argument("filter", StringArgumentType.greedyString())
                                        .suggests((ctx, builder) ->
                                                SharedSuggestionProvider.suggest(filterSuggestions(ctx), builder))
                                        .executes(ctx -> lookup(ctx, StringArgumentType.getString(ctx, "player"),
                                                IntegerArgumentType.getInteger(ctx, "page"),
                                                StringArgumentType.getString(ctx, "filter")))))
                        .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                                .<CommandSourceStack, String>argument("filter", StringArgumentType.greedyString())
                                .suggests((ctx, builder) ->
                                        SharedSuggestionProvider.suggest(filterSuggestions(ctx), builder))
                                .executes(ctx -> lookup(ctx, StringArgumentType.getString(ctx, "player"), 1,
                                        StringArgumentType.getString(ctx, "filter"))))));

        LiteralArgumentBuilder<CommandSourceStack> export = literal("export")
                .requires(PermissionBridge.requires(DeathCensusPermission.EXPORT,
                        src -> src.hasPermission(2)));
        for (String format : List.of("html", "json", "csv")) {
            // The --anonymize literal is registered before "player" so the word
            // argument cannot swallow the flag (DESIGN 1.3.0 §6). Both orders
            // (before and after the optional player) are accepted.
            export.then(literal(format)
                    .executes(ctx -> export(ctx, format, null, false))
                    .then(literal("--anonymize")
                            .executes(ctx -> export(ctx, format, null, true))
                            .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                                    .<CommandSourceStack, String>argument("player", StringArgumentType.word())
                                    .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(playerNames(ctx), builder))
                                    .executes(ctx -> export(ctx, format, StringArgumentType.getString(ctx, "player"), true))))
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                            .<CommandSourceStack, String>argument("player", StringArgumentType.word())
                            .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(playerNames(ctx), builder))
                            .executes(ctx -> export(ctx, format, StringArgumentType.getString(ctx, "player"), false))
                            .then(literal("--anonymize")
                                    .executes(ctx -> export(ctx, format,
                                            StringArgumentType.getString(ctx, "player"), true)))));
        }
        root.then(export);

        root.then(literal("backup")
                .requires(PermissionBridge.requires(DeathCensusPermission.BACKUP,
                        src -> src.hasPermission(2)))
                .executes(CensusCommands::backup));

        root.then(literal("restore")
                .requires(PermissionBridge.requires(DeathCensusPermission.BACKUP,
                        src -> src.hasPermission(2)))
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, String>argument("file", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(backupNames(ctx), builder))
                        .executes(ctx -> restore(ctx, StringArgumentType.getString(ctx, "file")))));

        root.then(literal("discordtest")
                .requires(PermissionBridge.requires(DeathCensusPermission.DIGEST,
                        src -> src.hasPermission(2)))
                .executes(ctx -> discordTest(ctx, false))
                // Inherits the DIGEST-node gate from its parent (PR-1 seam).
                .then(literal("digest").executes(ctx -> discordTest(ctx, true))));

        // PR-6: /dc digest season <from> <to> [html]. The whole subtree is
        // gated by the DIGEST permission node via the PR-1 PermissionBridge;
        // both bounds are Brigadier word() arguments (yyyy-MM-dd or epoch:millis).
        LiteralArgumentBuilder<CommandSourceStack> digest = literal("digest")
                .requires(PermissionBridge.requires(DeathCensusPermission.DIGEST,
                        src -> src.hasPermission(2)));
        digest.then(literal("season")
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, String>argument("from", StringArgumentType.word())
                        .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                                .<CommandSourceStack, String>argument("to", StringArgumentType.word())
                                .executes(ctx -> digestSeason(ctx,
                                        StringArgumentType.getString(ctx, "from"),
                                        StringArgumentType.getString(ctx, "to"), false))
                                .then(literal("html").executes(ctx -> digestSeason(ctx,
                                        StringArgumentType.getString(ctx, "from"),
                                        StringArgumentType.getString(ctx, "to"), true))))));
        root.then(digest);

        root.then(literal("reload")
                .requires(PermissionBridge.requires(DeathCensusPermission.DIGEST,
                        src -> src.hasPermission(2)))
                .executes(CensusCommands::reload));

        root.then(literal("delete")
                .requires(PermissionBridge.requires(DeathCensusPermission.RESET,
                        src -> src.hasPermission(2)))
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, String>argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(playerNames(ctx), builder))
                        .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                                .<CommandSourceStack, Integer>argument("index", IntegerArgumentType.integer(1))
                                .executes(ctx -> delete(ctx, StringArgumentType.getString(ctx, "player"),
                                        IntegerArgumentType.getInteger(ctx, "index"))))));

        LiteralArgumentBuilder<CommandSourceStack> reset = literal("reset")
                .requires(PermissionBridge.requires(DeathCensusPermission.RESET,
                        src -> src.hasPermission(2)));
        reset.then(literal("player")
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, String>argument("name", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(playerNames(ctx), builder))
                        .executes(ctx -> resetPlayer(ctx, StringArgumentType.getString(ctx, "name")))));
        reset.then(literal("all").then(literal("confirm").executes(CensusCommands::resetAll)));
        root.then(reset);

        // §8: /dc dev demo <count>. Both gates must be open at registration
        // time; a refusing stub (no data-writing handler) is registered when
        // either gate is closed so the gated lang message remains reachable.
        root.then(devTree(Config.devMode(), System.getProperty(DemoGate.SYSTEM_PROPERTY), src -> src.hasPermission(2)));

        // PR-10 (DESIGN 1.3.0 §9): admin-only vanilla statistics import.
        root.then(importTree(src -> src.hasPermission(2)));
        root.then(literal("doctor")
                .requires(PermissionBridge.requires(DeathCensusPermission.LOOKUP,
                        src -> src.hasPermission(2)))
                .executes(CensusCommands::doctor));

        return root;
    }

    // ------------------------------------------------------------ §8 dev/demo data

    /**
     * Builds the {@code /dc dev demo <count>} subtree. The real handler exists
     * only when {@link DemoGate} opens both gates; otherwise only a stub that
     * fails with {@code command.deathcensus.dev.gated} is registered. The DEV
     * node routes through the PR-1 PermissionBridge (vanilla OP level 2).
     */
    static LiteralArgumentBuilder<CommandSourceStack> devTree(boolean configFlag, String systemProperty,
                                                   Predicate<CommandSourceStack> opLevelTwoFallback) {
        boolean open = DemoGate.enabled(configFlag, systemProperty);
        LiteralArgumentBuilder<CommandSourceStack> dev = literal("dev")
                .requires(PermissionBridge.requires(DeathCensusPermission.DEV, opLevelTwoFallback));
        RequiredArgumentBuilder<CommandSourceStack, Integer> count = arg("count", IntegerArgumentType.integer(1, 500));
        if (open) {
            dev.executes(CensusCommands::devHelp);
            dev.then(literal("help").executes(CensusCommands::devHelp));
            count.executes(ctx -> demo(ctx, IntegerArgumentType.getInteger(ctx, "count")));
        } else {
            dev.executes(CensusCommands::devGated);
            count.executes(CensusCommands::devGated);
        }
        dev.then(literal("demo").then(count));
        return dev;
    }

    private static int devHelp(CommandContext<CommandSourceStack> ctx) {
        send(ctx, "command.deathcensus.dev.help");
        return 1;
    }

    private static int devGated(CommandContext<CommandSourceStack> ctx) {
        fail(ctx, "command.deathcensus.dev.gated");
        return 0;
    }

    /**
     * Population never goes through DeathTracker: no realtime post, milestone
     * evaluation, digest change or chat death line is produced here. Records
     * are ordinary CensusData entries and are cleaned up with
     * {@code /dc reset all confirm} (a backup is written first here too).
     */
    private static int demo(CommandContext<CommandSourceStack> ctx, int count) {
        // Re-check at execution time: the property/config may have changed
        // since the command tree was built; nothing is written when closed.
        if (!DemoGate.enabled(Config.devMode(), System.getProperty(DemoGate.SYSTEM_PROPERTY))) {
            fail(ctx, "command.deathcensus.dev.gated");
            return 0;
        }
        MinecraftServer server = server(ctx);
        if (server == null) {
            return 0;
        }
        if (count < DemoDataGenerator.MIN_COUNT || count > DemoDataGenerator.MAX_COUNT) {
            fail(ctx, "command.deathcensus.dev.demo.count",
                    DemoDataGenerator.MIN_COUNT, DemoDataGenerator.MAX_COUNT);
            return 0;
        }
        // Backup FIRST, same convention as delete/reset; abort on failure.
        if (CensusExport.writeBackup(server) == null) {
            fail(ctx, "command.deathcensus.delete.backup_failed");
            return 0;
        }
        List<DeathEntry> entries = DemoDataGenerator.generate(count, System.currentTimeMillis(),
                Config.displayTimeZone(), DemoDataGenerator.DEFAULT_SEED);
        CensusData data = CensusData.get(server);
        for (DeathEntry entry : entries) {
            // Normal record/trim bookkeeping; lifetime counters behave as on a
            // real death, but no announcement code runs on this path.
            data.recordDeath(entry, Config.maxRecordsPerPlayer());
        }
        DeathCensus.LOGGER.warn("[DeathCensus] DEMO DATA GENERATED: {} synthetic deaths (source: {})",
                count, ctx.getSource().getTextName());
        send(ctx, "command.deathcensus.dev.demo.done", count);
        return count;
    }

    // ------------------------------------------------------------ §9 vanilla import

    /**
     * PR-10: {@code /dc import vanilla [dry-run]}. The whole subtree is gated
     * by the IMPORT node through the PR-1 bridge (vanilla OP level 2 fallback).
     */
    static LiteralArgumentBuilder<CommandSourceStack> importTree(
            Predicate<CommandSourceStack> opLevelTwoFallback) {
        LiteralArgumentBuilder<CommandSourceStack> importNode = literal("import")
                .requires(PermissionBridge.requires(DeathCensusPermission.IMPORT, opLevelTwoFallback));
        LiteralArgumentBuilder<CommandSourceStack> vanilla = literal("vanilla")
                .executes(ctx -> importVanilla(ctx, false))
                .then(literal("dry-run").executes(ctx -> importVanilla(ctx, true)));
        importNode.then(vanilla);
        return importNode;
    }

    /**
     * Reads only {@code <world>/stats/<uuid>.json} for UUIDs already known to
     * CensusData, then merges pre-install baselines with max semantics. No
     * DeathEntry is fabricated and no milestone announcement is evaluated;
     * dry run performs the same read/report without writing a backup or data.
     */
    private static int importVanilla(CommandContext<CommandSourceStack> ctx, boolean dryRun) {
        MinecraftServer server = server(ctx);
        if (server == null) {
            return 0;
        }
        if (!dryRun && CensusExport.writeBackup(server) == null) {
            fail(ctx, "command.deathcensus.import.vanilla.failed");
            return 0;
        }
        // M15: a dry run must be strictly read-only. A non-creating lookup
        // avoids persisting an empty SavedData file on a fresh world.
        CensusData data = dryRun ? CensusData.getIfExists(server) : CensusData.get(server);
        Path statsDir = server.getWorldPath(LevelResource.ROOT).resolve("stats");
        Map<UUID, VanillaStats> stats = VanillaStatsSource.readFor(statsDir,
                data == null ? java.util.Set.of() : data.allPlayers().keySet());
        VanillaImportResult result = data == null
                ? new VanillaImportResult(java.util.List.of(), 0, 0L)
                : data.importVanillaBaselines(stats, dryRun);
        for (VanillaImportResult.Row row : result.rows()) {
            String label = row.name().isEmpty() ? row.uuid() : row.name() + " (" + row.uuid() + ")";
            send(ctx, "command.deathcensus.import.vanilla.per_player",
                    label, row.vanillaDeaths(), row.baselineDeaths(), row.topCause());
        }
        if (result.rows().isEmpty()) {
            send(ctx, "command.deathcensus.import.vanilla.no_players", result.skippedPlayers());
            return 0;
        }
        send(ctx, dryRun ? "command.deathcensus.import.vanilla.dry_run"
                         : "command.deathcensus.import.vanilla.done",
                result.rows().size(), result.skippedPlayers(), result.addedDeaths());
        return 1;
    }

    // ------------------------------------------------------------ helpers

    private static void send(CommandContext<CommandSourceStack> ctx, String key, Object... args) {
        ctx.getSource().sendSuccess(() -> ServerLang.tr(ctx.getSource(), key, args), false);
    }

    private static void sendLiteral(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
    }

    private static void fail(CommandContext<CommandSourceStack> ctx, String key, Object... args) {
        ctx.getSource().sendFailure(ServerLang.tr(ctx.getSource(), key, args));
    }

    @Nullable
    private static MinecraftServer server(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getServer();
    }

    private static List<String> playerNames(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = server(ctx);
        List<String> names = new ArrayList<>();
        if (server == null) {
            return names;
        }
        // DESIGN §8: opted-out names must not leak through tab completion for
        // non-admins, while admins keep seeing everyone.
        boolean admin = isAdmin(ctx);
        Map<UUID, CensusData.PlayerCensus> stored = CensusData.get(server).allPlayers();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            CensusData.PlayerCensus pc = stored.get(p.getUUID());
            if (!admin && pc != null && pc.optOutPublic) {
                continue;
            }
            names.add(p.getGameProfile().getName());
        }
        for (CensusData.PlayerCensus pc : stored.values()) {
            if (!admin && pc.optOutPublic) {
                continue;
            }
            if (!pc.lastKnownName.isEmpty() && !names.contains(pc.lastKnownName)) {
                names.add(pc.lastKnownName);
            }
        }
        return names;
    }

    private static List<String> backupNames(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = server(ctx);
        return server == null ? List.of() : CensusExport.listBackups(server);
    }

    /** Resolves a player name to a UUID: online players first, then stored names. */
    @Nullable
    private static UUID resolvePlayer(MinecraftServer server, String name) {
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            String onlineName = online.getGameProfile().getName();
            if (onlineName != null && onlineName.equalsIgnoreCase(name)) {
                return online.getUUID();
            }
        }
        return CensusData.get(server).findPlayerByName(name);
    }

    private static boolean isAdmin(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        return PermissionBridge.allowed(source, DeathCensusPermission.LOOKUP, source.hasPermission(2));
    }

    /** Resolves a stored period literal to its epoch-millis cutoff (all = 0). */
    private static long sinceMillis(String period) {
        if ("today".equals(period)) {
            ZoneId zone = Config.displayTimeZone();
            return LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli();
        }
        long now = System.currentTimeMillis();
        return switch (period) {
            case "7d" -> now - 7L * DAY_MILLIS;
            case "30d" -> now - 30L * DAY_MILLIS;
            default -> 0L;
        };
    }

    /**
     * Resolves the optional history/lookup filter to a death predicate, or null
     * when the filter is neither a known category nor a dimension in the data.
     */
    @Nullable
    private static Predicate<DeathEntry> filterPredicate(MinecraftServer server, @Nullable String filter) {
        if (filter == null) {
            return null;
        }
        if (CauseClassifier.isKnownCategory(filter)) {
            return death -> filter.equals(death.causeCategory());
        }
        for (PlayerDeaths pd : CensusData.get(server).snapshot()) {
            for (DeathEntry death : pd.deaths()) {
                if (filter.equals(death.dimension())) {
                    return entry -> filter.equals(entry.dimension());
                }
            }
        }
        return null;
    }

    private static List<String> filterSuggestions(CommandContext<CommandSourceStack> ctx) {
        List<String> suggestions = new ArrayList<>(CauseClassifier.CATEGORIES);
        MinecraftServer server = server(ctx);
        if (server == null) {
            return suggestions;
        }
        for (PlayerDeaths pd : CensusData.get(server).snapshot()) {
            for (DeathEntry death : pd.deaths()) {
                String dimension = death.dimension();
                if (!dimension.isEmpty() && !suggestions.contains(dimension)) {
                    suggestions.add(dimension);
                }
            }
        }
        return suggestions;
    }

    private static String formatEntryLine(DeathEntry raw, PlayerPrivacy privacy, boolean privileged, int index,
                                          Map<String, String> names, Map<String, PlayerPrivacy> privacyById,
                                          String language, HiddenIdentities hidden) {
        DeathEntry e = DeathRedactor.apply(raw, privacy, privileged, hidden);
        String coords = DeathRedactor.isCoordinateHidden(e)
                ? DeathRedactor.COORDINATE_MARKER + ", " + DeathRedactor.COORDINATE_MARKER
                        + ", " + DeathRedactor.COORDINATE_MARKER
                : (int) Math.floor(e.x()) + ", " + (int) Math.floor(e.y()) + ", " + (int) Math.floor(e.z());
        return "#" + index
                + " · Day " + (e.gameDay() + 1)
                + " · " + TimeFormat.formatDateTime(e.epochMillis(), Config.displayTimeZone())
                + " · " + e.dimension()
                + " (" + coords + ")"
                + " · " + (e.deathMessage().isEmpty() ? e.causeCategory() : e.deathMessage())
                + " · survived " + TimeFormat.formatDuration(e.survivalTicks())
                + (e.excuse().isEmpty() ? "" : " · \"" + e.excuse() + "\"")
                + (e.lastWords().isEmpty() ? "" : " · last words: \"" + e.lastWords() + "\"")
                + (e.fCount() > 0 ? " · F x" + e.fCount() : "")
                + witnessSuffix(raw, names, privacyById, privileged, language)
                + contextSuffix(e.context(), language);
    }

    /** PR-3: localized context fragments appended only to the /dc history detail line. */
    private static String contextSuffix(DeathContext context, String language) {
        if (context == null || context.isEmpty()) {
            return " · " + ServerLang.formatForLang(language,
                    "command.deathcensus.ui.detail.context.empty");
        }
        StringBuilder sb = new StringBuilder(64);
        appendContextPart(sb, language, "command.deathcensus.ui.detail.context.main_hand", context.mainHand());
        appendContextPart(sb, language, "command.deathcensus.ui.detail.context.armor",
                String.join(", ", context.armor()));
        appendContextPart(sb, language, "command.deathcensus.ui.detail.context.effects",
                String.join(", ", context.effects()));
        appendContextPart(sb, language, "command.deathcensus.ui.detail.context.biome", context.biome());
        appendContextPart(sb, language, "command.deathcensus.ui.detail.context.weather", context.weather());
        return sb.length() == 0 ? "" : " · " + sb;
    }

    private static void appendContextPart(StringBuilder sb, String language, String key, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(" · ");
        }
        sb.append(ServerLang.formatForLang(language, key, value));
    }

    /** The "Witnesses: A, B" detail suffix; absent when no witness was captured. */
    private static String witnessSuffix(DeathEntry raw, Map<String, String> names,
                                        Map<String, PlayerPrivacy> privacyById, boolean privileged,
                                        String language) {
        List<String> witnesses = WitnessRedactor.displayNames(raw, names, privacyById, privileged);
        if (witnesses.isEmpty()) {
            return "";
        }
        return " · " + ServerLang.formatForLang(language, "command.deathcensus.witnesses.detail",
                WitnessRedactor.joinNames(localizeWitnessNames(witnesses, language)));
    }

    /** The common marker is language-neutral; chat surfaces use its bundled translation. */
    private static List<String> localizeWitnessNames(List<String> names, String language) {
        List<String> localized = new ArrayList<>(names.size());
        String hidden = ServerLang.formatForLang(language, "command.deathcensus.witnesses.hidden");
        for (String name : names) {
            localized.add(WitnessRedactor.HIDDEN_NAME_MARKER.equals(name) ? hidden : name);
        }
        return localized;
    }

    /** Chat fallback language for literal lines built from translated fragments. */
    private static String languageOf(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer self = ctx.getSource().getPlayer();
        return self != null && self.clientInformation() != null
                ? self.clientInformation().language() : ServerLang.DEFAULT_LANG;
    }

    private static int showHistory(CommandContext<CommandSourceStack> ctx, UUID uuid, String displayName, int page) {
        return showHistory(ctx, uuid, displayName, page, null);
    }

    private static int showHistory(CommandContext<CommandSourceStack> ctx, UUID uuid, String displayName,
                                   int page, @Nullable String filter) {
        MinecraftServer server = server(ctx);
        if (server == null) {
            return 0;
        }
        Predicate<DeathEntry> predicate = filterPredicate(server, filter);
        if (filter != null && predicate == null) {
            fail(ctx, "command.deathcensus.filter_unknown", filter);
            return 0;
        }
        CensusData.PlayerCensus pc = CensusData.get(server).playerOrNull(uuid);
        if (pc == null || pc.deaths.isEmpty()) {
            send(ctx, "command.deathcensus.history.empty", displayName);
            return 0;
        }
        List<DeathEntry> newestFirst = new ArrayList<>();
        for (DeathEntry entry : pc.deaths) {
            if (predicate == null || predicate.test(entry)) {
                newestFirst.add(entry);
            }
        }
        if (newestFirst.isEmpty()) {
            send(ctx, "command.deathcensus.history.empty", displayName);
            return 0;
        }
        java.util.Collections.reverse(newestFirst);
        Pagination.Page<DeathEntry> p = Pagination.of(newestFirst, page, PAGE_SIZE);
        send(ctx, "command.deathcensus.history.header", displayName, p.total(), p.page(), p.pageCount());
        PlayerPrivacy privacy = new PlayerPrivacy(pc.optOutPublic, pc.hideCoordinates,
                pc.hidePvpOpponent, pc.discordOptOut);
        ServerPlayer self = ctx.getSource().getPlayer();
        boolean privileged = isAdmin(ctx) || (self != null && self.getUUID().equals(uuid));
        List<PlayerDeaths> snapshot = CensusData.get(server).snapshot();
        Map<String, String> witnessNames = WitnessRedactor.namesById(snapshot);
        Map<String, PlayerPrivacy> witnessPrivacy = WitnessRedactor.privacyById(snapshot);
        String language = languageOf(ctx);
        int base = (p.page() - 1) * PAGE_SIZE;
        for (int i = 0; i < p.items().size(); i++) {
            sendLiteral(ctx, formatEntryLine(p.items().get(i), privacy, privileged, p.total() - (base + i),
                    witnessNames, witnessPrivacy, language, HiddenIdentities.of(snapshot)));
        }
        if (p.pageCount() > 1) {
            send(ctx, "command.deathcensus.page.hint", p.page(), p.pageCount());
        }
        return 1;
    }

    // ------------------------------------------------------------ commands

    /** No arguments: players get the interactive chat menu, console keeps help. */
    private static int entry(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer self = ctx.getSource().getPlayer();
        if (self == null) {
            return help(ctx);
        }
        ChatUi.openHome(self);
        ChatUi.sendHelpLines(self);
        return 1;
    }

    /**
     * /dc witnesses — public but privacy-gated like history. Witnesses are
     * composed from the live snapshot; an opted-out victim is refused for
     * non-self/non-admin viewers and opted-out witnesses are anonymized by the
     * common redactor. No name is taken from the captured list without the
     * current privacy lookup.
     */
    private static int witnesses(CommandContext<CommandSourceStack> ctx, @Nullable String playerName,
                                 String period, int page) {
        MinecraftServer server = server(ctx);
        if (server == null) {
            return 0;
        }
        ServerPlayer self = ctx.getSource().getPlayer();
        UUID target;
        String displayName;
        if (playerName != null) {
            target = resolvePlayer(server, playerName);
            if (target == null) {
                fail(ctx, "command.deathcensus.player_not_found", playerName);
                return 0;
            }
            CensusData.PlayerCensus pc = CensusData.get(server).playerOrNull(target);
            displayName = pc == null || pc.lastKnownName.isEmpty() ? playerName : pc.lastKnownName;
        } else {
            if (self == null) {
                fail(ctx, "command.deathcensus.players_only");
                return 0;
            }
            target = self.getUUID();
            displayName = self.getGameProfile().getName();
        }
        boolean privileged = isAdmin(ctx) || (self != null && self.getUUID().equals(target));
        CensusData data = CensusData.get(server);
        CensusData.PlayerCensus pc = data.playerOrNull(target);
        if (pc != null && pc.optOutPublic && !privileged) {
            fail(ctx, "command.deathcensus.opted_out", displayName);
            return 0;
        }
        List<PlayerDeaths> snapshot = data.snapshot();
        List<PlayerDeaths> inPeriod = CensusStats.filterSince(snapshot, sinceMillis(period));
        List<WitnessAnalysis.Row> rows = WitnessAnalysis.rank(inPeriod, target.toString(), 0L,
                WitnessRedactor.namesById(snapshot), WitnessRedactor.privacyById(snapshot), privileged);
        Pagination.Page<WitnessAnalysis.Row> p = Pagination.of(rows, page, PAGE_SIZE);
        send(ctx, "command.deathcensus.witnesses.header", displayName, period, rows.size(), p.page());
        if (p.items().isEmpty()) {
            send(ctx, "command.deathcensus.witnesses.empty", displayName);
            return 0;
        }
        int rank = (p.page() - 1) * PAGE_SIZE;
        String language = languageOf(ctx);
        for (WitnessAnalysis.Row row : p.items()) {
            send(ctx, "command.deathcensus.witnesses.row", ++rank,
                    localizeWitnessNames(List.of(row.name()), language).get(0), row.count());
        }
        if (p.pageCount() > 1) {
            send(ctx, "command.deathcensus.page.hint", p.page(), p.pageCount());
        }
        return 1;
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        send(ctx, "command.deathcensus.help.title");
        send(ctx, "command.deathcensus.help.excuse");
        send(ctx, "command.deathcensus.help.history");
        send(ctx, "command.deathcensus.help.witnesses");
        if (Config.awardsEnabled()) {
            send(ctx, "command.deathcensus.awards.help");
        }
        if (Config.chronicleEnabled()) {
            send(ctx, "command.deathcensus.chronicle.help");
        }
        send(ctx, "command.deathcensus.help.stats");
        send(ctx, "command.deathcensus.help.leaderboard");
        send(ctx, "command.deathcensus.help.optout");
        send(ctx, "command.deathcensus.help.privacy");
        send(ctx, "command.deathcensus.help.discordfilters");
        if (isAdmin(ctx)) {
            send(ctx, "command.deathcensus.help.admin");
            send(ctx, "command.deathcensus.import.vanilla.help");
            send(ctx, "command.deathcensus.digest.season.help");
        }
        return 1;
    }

    private static int excuse(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = server(ctx);
        ServerPlayer self = ctx.getSource().getPlayer();
        if (server == null || self == null) {
            fail(ctx, "command.deathcensus.players_only");
            return 0;
        }
        String text = StringArgumentType.getString(ctx, "text");
        if (text.length() > 100) {
            fail(ctx, "command.deathcensus.excuse.too_long");
            return 0;
        }
        if (!CensusData.get(server).setExcuse(self.getUUID(), text)) {
            fail(ctx, "command.deathcensus.excuse.no_deaths");
            return 0;
        }
        send(ctx, "command.deathcensus.excuse.done");
        return 1;
    }

    /** UI excuse flow: same 100-char bound and self-only rule, selected by (uuid, epoch). */
    private static int excuseSelected(CommandContext<CommandSourceStack> ctx, String uuidText,
                                      long epoch, String text) {
        ServerPlayer self = ctx.getSource().getPlayer();
        UUID uuid = UiActions.parseUuid(uuidText).orElse(null);
        if (self == null || uuid == null || !UiPrivacy.canEditExcuse(self.getUUID(), uuid.toString())) {
            fail(ctx, "command.deathcensus.ui.record_unavailable");
            return 0;
        }
        if (text.length() > 100) {
            fail(ctx, "command.deathcensus.excuse.too_long");
            return 0;
        }
        ChatUi.saveExcuseFromChat(self, uuid, epoch, text);
        return 1;
    }

    private static int history(CommandContext<CommandSourceStack> ctx, @Nullable String playerName, int page) {
        return history(ctx, playerName, page, null);
    }

    private static int history(CommandContext<CommandSourceStack> ctx, @Nullable String playerName, int page,
                               @Nullable String filter) {
        MinecraftServer server = server(ctx);
        if (server == null) {
            return 0;
        }
        if (playerName == null) {
            ServerPlayer self = ctx.getSource().getPlayer();
            if (self == null) {
                fail(ctx, "command.deathcensus.players_only");
                return 0;
            }
            return showHistory(ctx, self.getUUID(), self.getGameProfile().getName(), page, filter);
        }
        UUID target = resolvePlayer(server, playerName);
        if (target == null) {
            fail(ctx, "command.deathcensus.player_not_found", playerName);
            return 0;
        }
        ServerPlayer self = ctx.getSource().getPlayer();
        boolean selfView = self != null && self.getUUID().equals(target);
        CensusData.PlayerCensus pc = CensusData.get(server).playerOrNull(target);
        if (!selfView && !isAdmin(ctx) && pc != null && pc.optOutPublic) {
            fail(ctx, "command.deathcensus.opted_out", playerName);
            return 0;
        }
        return showHistory(ctx, target, playerName, page, filter);
    }

    private static int lookup(CommandContext<CommandSourceStack> ctx, String playerName, int page) {
        return lookup(ctx, playerName, page, null);
    }

    private static int lookup(CommandContext<CommandSourceStack> ctx, String playerName, int page,
                              @Nullable String filter) {
        MinecraftServer server = server(ctx);
        if (server == null) {
            return 0;
        }
        UUID target = resolvePlayer(server, playerName);
        if (target == null) {
            fail(ctx, "command.deathcensus.player_not_found", playerName);
            return 0;
        }
        return showHistory(ctx, target, playerName, page, filter);
    }

    private static int ownStats(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = server(ctx);
        ServerPlayer self = ctx.getSource().getPlayer();
        if (server == null || self == null) {
            fail(ctx, "command.deathcensus.players_only");
            return 0;
        }
        CensusData data = CensusData.get(server);
        CensusData.PlayerCensus pc = data.playerOrNull(self.getUUID());
        if (pc == null || pc.deaths.isEmpty()) {
            send(ctx, "command.deathcensus.history.empty", self.getGameProfile().getName());
            return 0;
        }
        PlayerDeaths selfDeaths = new PlayerDeaths(self.getUUID().toString(),
                self.getGameProfile().getName(), pc.optOutPublic,
                pc.hideCoordinates, pc.hidePvpOpponent, pc.discordOptOut, pc.deaths);
        List<CensusStats.PlayerSummary> summaries = CensusStats.summaries(List.of(selfDeaths), true);
        CensusStats.PlayerSummary s = summaries.get(0);
        send(ctx, "command.deathcensus.stats.header", s.name());
        send(ctx, "command.deathcensus.stats.deaths", s.deaths());
        send(ctx, "command.deathcensus.stats.top_cause", s.topCategory());
        send(ctx, "command.deathcensus.stats.longest", TimeFormat.formatDuration(s.longestSurvivalTicks()));
        send(ctx, "command.deathcensus.stats.shortest",
                s.shortestSurvivalTicks() >= 0 ? TimeFormat.formatDuration(s.shortestSurvivalTicks()) : "-");
        return 1;
    }

    private static int serverStats(CommandContext<CommandSourceStack> ctx) {
        return serverStats(ctx, "all");
    }

    private static int serverStats(CommandContext<CommandSourceStack> ctx, String period) {
        MinecraftServer server = server(ctx);
        if (server == null) {
            return 0;
        }
        // DESIGN §8: opted-out players are hidden from rankings, but their deaths
        // still count in the anonymous server totals.
        CensusData data = CensusData.get(server);
        List<PlayerDeaths> all = CensusStats.filterSince(data.snapshot(), sinceMillis(period));
        int total = CensusStats.totalDeaths(all);
        send(ctx, "command.deathcensus.server_stats.header");
        if (!"all".equals(period)) {
            sendLiteral(ctx, "Period: " + period);
        }
        send(ctx, "command.deathcensus.stats.deaths", total);
        // §9: baseline deaths are anonymous totals only; cause percentages below stay record-based.
        sendLiteral(ctx, "Vanilla baseline: " + data.vanillaBaselineTotal() + " pre-install death(s)");
        for (Map.Entry<String, Integer> e : CensusStats.causeCounts(all, true)) {
            int pct = total > 0 ? (int) Math.round(100.0 * e.getValue() / total) : 0;
            sendLiteral(ctx, "· " + e.getKey() + ": " + e.getValue() + " (" + pct + "%)");
        }
        List<CensusStats.PlayerSummary> board = CensusStats.leaderboardDeaths(all, false);
        if (!board.isEmpty()) {
            send(ctx, "command.deathcensus.server_stats.top_player", board.get(0).name(), board.get(0).deaths());
        }
        CensusStats.rarestCause(all, true)
                .ifPresent(cause -> send(ctx, "command.deathcensus.server_stats.rarest", cause));
        return 1;
    }

    private static int leaderboard(CommandContext<CommandSourceStack> ctx, String type, int page) {
        return leaderboard(ctx, type, "all", page);
    }

    private static int leaderboard(CommandContext<CommandSourceStack> ctx, String type, String period, int page) {
        MinecraftServer server = server(ctx);
        if (server == null) {
            return 0;
        }
        List<PlayerDeaths> all = CensusStats.filterSince(CensusData.get(server).snapshot(), sinceMillis(period));
        send(ctx, "command.deathcensus.leaderboard.header", type);
        if (!"all".equals(period)) {
            sendLiteral(ctx, "Period: " + period);
        }
        switch (type) {
            case "deaths" -> {
                Pagination.Page<CensusStats.PlayerSummary> p =
                        Pagination.of(CensusStats.leaderboardDeaths(all, false), page, PAGE_SIZE);
                if (p.total() == 0) {
                    send(ctx, "command.deathcensus.leaderboard.empty");
                    return 0;
                }
                int rank = (p.page() - 1) * PAGE_SIZE;
                for (CensusStats.PlayerSummary s : p.items()) {
                    sendLiteral(ctx, (++rank) + ". " + s.name() + " — " + s.deaths());
                }
                if (p.pageCount() > 1) {
                    send(ctx, "command.deathcensus.page.hint", p.page(), p.pageCount());
                }
            }
            case "survival", "fastest" -> {
                List<DeathEntry> entries = "survival".equals(type)
                        ? CensusStats.leaderboardSurvival(all, false)
                        : CensusStats.leaderboardFastest(all, false);
                Pagination.Page<DeathEntry> p = Pagination.of(entries, page, PAGE_SIZE);
                if (p.total() == 0) {
                    send(ctx, "command.deathcensus.leaderboard.empty");
                    return 0;
                }
                int rank = (p.page() - 1) * PAGE_SIZE;
                for (DeathEntry e : p.items()) {
                    sendLiteral(ctx, (++rank) + ". " + e.playerName() + " — "
                            + TimeFormat.formatDuration(e.survivalTicks())
                            + " (" + e.causeCategory() + ", Day " + (e.gameDay() + 1) + ")");
                }
                if (p.pageCount() > 1) {
                    send(ctx, "command.deathcensus.page.hint", p.page(), p.pageCount());
                }
            }
            case "rarest" -> {
                // DESIGN §6.1: the rarest cause is determined over all deaths
                // (anonymous total); public players are ranked by how many
                // deaths of that cause they have.
                java.util.Optional<String> rarest = CensusStats.rarestCause(all, true);
                if (rarest.isEmpty()) {
                    send(ctx, "command.deathcensus.leaderboard.empty");
                    return 0;
                }
                String cause = rarest.get();
                send(ctx, "command.deathcensus.leaderboard.rarest_cause", cause);
                List<Map.Entry<String, Integer>> ranked = new ArrayList<>();
                for (PlayerDeaths pd : all) {
                    if (pd.optOutPublic()) {
                        continue;
                    }
                    int count = 0;
                    for (DeathEntry e : pd.deaths()) {
                        if (cause.equals(e.causeCategory())) {
                            count++;
                        }
                    }
                    if (count > 0) {
                        ranked.add(Map.entry(pd.name(), count));
                    }
                }
                ranked.sort(java.util.Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .reversed()
                        .thenComparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER));
                Pagination.Page<Map.Entry<String, Integer>> p = Pagination.of(ranked, page, PAGE_SIZE);
                if (p.total() == 0) {
                    send(ctx, "command.deathcensus.leaderboard.empty");
                    return 0;
                }
                int rank = (p.page() - 1) * PAGE_SIZE;
                for (Map.Entry<String, Integer> e : p.items()) {
                    sendLiteral(ctx, (++rank) + ". " + e.getKey() + " — " + e.getValue());
                }
                if (p.pageCount() > 1) {
                    send(ctx, "command.deathcensus.page.hint", p.page(), p.pageCount());
                }
            }
            default -> {
                return 0;
            }
        }
        return 1;
    }

    // --------------------------------------------------------------- chronicle (§3)

    private static int chronicle(CommandContext<CommandSourceStack> ctx, String order, int page) {
        MinecraftServer server = server(ctx);
        if (server == null || !Config.chronicleEnabled()) {
            return 0;
        }
        List<PlayerDeaths> snapshot = CensusData.get(server).snapshot();
        List<ChronicleEvent> rows = ChronicleRules.sorted(chronicleRows(server, snapshot,
                selfUuid(ctx), isAdmin(ctx), false));
        if ("newest".equalsIgnoreCase(order)) {
            List<ChronicleEvent> reversed = new ArrayList<>(rows);
            Collections.reverse(reversed);
            rows = reversed;
        }
        Pagination.Page<ChronicleEvent> p = Pagination.of(rows, page, PAGE_SIZE);
        send(ctx, "command.deathcensus.chronicle.header", order, rows.size(), p.page(), p.pageCount());
        if (p.items().isEmpty()) {
            send(ctx, "command.deathcensus.chronicle.empty");
        }
        for (String line : chronicleLines(p.items(), languageOf(ctx))) {
            sendLiteral(ctx, line);
        }
        if (p.pageCount() > 1) {
            send(ctx, "command.deathcensus.page.hint", p.page(), p.pageCount());
        }
        return 1;
    }

    @Nullable
    private static UUID selfUuid(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer self = ctx.getSource().getPlayer();
        return self == null ? null : self.getUUID();
    }

    /**
     * Public projection of stored chronicle rows. Self/admin keep stored names;
     * an opted-out player is never named for anyone else. The {@code discord}
     * flag is the opt-in seam ({@code chronicleInDiscord}, default false) and
     * applies the discordOptOut filter; PR-5 adds no Discord posts.
     */
    static List<ChronicleEvent> chronicleRows(MinecraftServer server, List<PlayerDeaths> snapshot,
                                              @Nullable UUID viewerUuid, boolean admin, boolean discord) {
        if (server == null || !Config.chronicleEnabled() || (discord && !Config.chronicleInDiscord())) {
            return List.of();
        }
        Map<String, PlayerPrivacy> privacy = ChronicleRules.privacyById(snapshot);
        HiddenIdentities hidden = HiddenIdentities.of(snapshot);
        List<ChronicleEvent> projected = new ArrayList<>();
        for (ChronicleEvent row : ChronicleRules.sorted(CensusData.get(server).chronicleEvents())) {
            boolean privileged = admin || (viewerUuid != null
                    && viewerUuid.toString().equalsIgnoreCase(row.uuid()));
            projected.add(ChronicleRules.forPublic(row, privacy, hidden, privileged));
        }
        return discord ? ChronicleRules.forDiscord(projected, privacy) : List.copyOf(projected);
    }

    /** Localized, already-projected rows; the reconstructed label is appended once. */
    static List<String> chronicleLines(List<ChronicleEvent> rows, String language) {
        List<String> lines = new ArrayList<>();
        for (ChronicleEvent row : rows) {
            if (row == null) {
                continue;
            }
            String kind = ServerLang.formatForLang(language, chronicleKindKey(row.kind()));
            String detail = row.kind() == ChronicleEvent.Kind.TOTAL_COUNT
                    ? Integer.toString(row.count())
                    : ChronicleRules.HIDDEN_NAME.equals(row.name())
                            ? ServerLang.formatForLang(language, "command.deathcensus.chronicle.hidden")
                            : row.name();
            String line = ServerLang.formatForLang(language, "command.deathcensus.chronicle.row",
                    kind, detail, row.gameDay() + 1L);
            if (row.reconstructed()) {
                line = line + " (" + ServerLang.formatForLang(language,
                        "command.deathcensus.chronicle.reconstructed") + ")";
            }
            lines.add(line);
        }
        return lines;
    }

    /** Full literal keys avoid dynamically constructed translation keys. */
    private static String chronicleKindKey(ChronicleEvent.Kind kind) {
        return switch (kind) {
            case SERVER_FIRST -> "command.deathcensus.chronicle.kind.server_first";
            case NETHER_FIRST -> "command.deathcensus.chronicle.kind.nether_first";
            case END_DRAGON_DEATH -> "command.deathcensus.chronicle.kind.end_dragon_death";
            case TOTAL_COUNT -> "command.deathcensus.chronicle.kind.total_count";
            case CATEGORY_COMPLETE -> "command.deathcensus.chronicle.kind.category_complete";
        };
    }

    // --------------------------------------------------------------- awards (§2)

    private static int awards(CommandContext<CommandSourceStack> ctx, String period, int page) {
        MinecraftServer server = server(ctx);
        if (server == null || !Config.awardsEnabled()) {
            return 0;
        }
        List<PlayerDeaths> snapshot = CensusData.get(server).snapshot();
        List<PlayerDeaths> inPeriod = CensusStats.filterSince(snapshot, sinceMillis(period));
        List<PlayerDeaths> publicPlayers = new ArrayList<>();
        for (PlayerDeaths player : inPeriod) {
            if (player != null && !player.optOutPublic()) {
                publicPlayers.add(player);
            }
        }
        List<AwardResult> results = computeAwards(server, publicPlayers, false);
        Pagination.Page<AwardResult> p = Pagination.of(results, page, PAGE_SIZE);
        send(ctx, "command.deathcensus.awards.header", period, results.size(), p.page(), p.pageCount());
        if (p.items().isEmpty()) {
            send(ctx, "command.deathcensus.awards.empty");
        }
        for (String line : awardLines(p.items(), languageOf(ctx))) {
            sendLiteral(ctx, line);
        }
        send(ctx, "command.deathcensus.awards.retained");
        if (p.pageCount() > 1) {
            send(ctx, "command.deathcensus.page.hint", p.page(), p.pageCount());
        }
        return 1;
    }

    /** Shared by chat, exports and digests: recomputed, never stored. */
    static List<AwardResult> computeAwards(MinecraftServer server, List<PlayerDeaths> publicSnapshot,
                                           boolean discord) {
        if (server == null || !Config.awardsEnabled() || (discord && !Config.awardsInDigest())) {
            return List.of();
        }
        CensusData data = CensusData.get(server);
        Map<String, Set<String>> categories = new LinkedHashMap<>();
        for (Map.Entry<UUID, CensusData.PlayerCensus> entry : data.allPlayers().entrySet()) {
            if (!entry.getValue().optOutPublic) {
                categories.put(entry.getKey().toString(), entry.getValue().lifetimeCategories());
            }
        }
        String rarest = CensusStats.rarestCause(data.snapshot(), true).orElse("");
        List<PlayerDeaths> eligible = new ArrayList<>();
        for (PlayerDeaths player : publicSnapshot) {
            if (player == null || player.optOutPublic() || (discord && player.discordOptOut())) {
                continue;
            }
            eligible.add(player);
        }
        return Awards.evaluate(eligible, Config.awardsMinCount(),
                new Awards.Context(categories, rarest));
    }

    /** Lines are built from bundled lang keys; anonymous totals are added by the caller. */
    static List<String> awardLines(List<AwardResult> results, String language) {
        List<String> lines = new ArrayList<>();
        for (AwardResult result : results) {
            String title = ServerLang.formatForLang(language, awardTitleKey(result.rule()));
            lines.add(ServerLang.formatForLang(language, "command.deathcensus.awards.row",
                    title, result.name(), result.count()));
        }
        return lines;
    }

    /** Full literal keys avoid dynamically constructed translation keys. */
    private static String awardTitleKey(Awards.Rule rule) {
        return switch (rule) {
            case MOST_DEATHS -> "command.deathcensus.awards.title.most_deaths";
            case LONGEST_SURVIVAL -> "command.deathcensus.awards.title.longest_survival";
            case MOST_FALLS -> "command.deathcensus.awards.title.most_falls";
            case MOST_LAVA -> "command.deathcensus.awards.title.most_lava";
            case MOST_LIGHTNING -> "command.deathcensus.awards.title.most_lightning";
            case MOST_RESPECTED -> "command.deathcensus.awards.title.most_respected";
            case CATEGORY_COMPLETE -> "command.deathcensus.awards.title.category_complete";
            case RAREST_CAUSE -> "command.deathcensus.awards.title.rarest_cause";
            case WITNESS_FAMOUS -> "command.deathcensus.awards.title.witness_famous";
        };
    }

    private record PrivacyTarget(UUID uuid, String name) {
    }

    private static boolean privacyOtherAllowed(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        return PermissionBridge.allowed(source, DeathCensusPermission.PRIVACY_OTHER,
                source.hasPermission(2));
    }

    /** Resolves a privacy target; self is always allowed, others need PRIVACY_OTHER. */
    @Nullable
    private static PrivacyTarget privacyTarget(CommandContext<CommandSourceStack> ctx, @Nullable String playerName) {
        MinecraftServer server = server(ctx);
        if (server == null) {
            return null;
        }
        ServerPlayer self = ctx.getSource().getPlayer();
        UUID viewer = self == null ? null : self.getUUID();
        boolean other = privacyOtherAllowed(ctx);
        if (playerName == null || playerName.isEmpty()) {
            if (self == null) {
                fail(ctx, "command.deathcensus.players_only");
                return null;
            }
            return new PrivacyTarget(viewer, self.getGameProfile().getName());
        }
        if (!other) {
            fail(ctx, "command.deathcensus.privacy.denied");
            return null;
        }
        UUID uuid = resolvePlayer(server, playerName);
        if (uuid == null || !PlayerPrivacy.canAccess(viewer, uuid, other)) {
            fail(ctx, "command.deathcensus.player_not_found", playerName);
            return null;
        }
        return new PrivacyTarget(uuid, playerName);
    }

    private static PlayerPrivacy storedPrivacy(MinecraftServer server, UUID uuid) {
        CensusData.PlayerCensus pc = CensusData.get(server).playerOrNull(uuid);
        return pc == null ? PlayerPrivacy.none() : new PlayerPrivacy(
                pc.optOutPublic, pc.hideCoordinates, pc.hidePvpOpponent, pc.discordOptOut);
    }

    private static String privacyState(boolean value) {
        return value ? "command.deathcensus.privacy.on" : "command.deathcensus.privacy.off";
    }

    /** {@code /dc privacy [player]} — read the four flags with no redaction for the viewer. */
    private static int privacy(CommandContext<CommandSourceStack> ctx, @Nullable String playerName) {
        MinecraftServer server = server(ctx);
        PrivacyTarget target = privacyTarget(ctx, playerName);
        if (server == null || target == null) {
            return 0;
        }
        PlayerPrivacy flags = storedPrivacy(server, target.uuid());
        send(ctx, "command.deathcensus.privacy.view", target.name(),
                ServerLang.tr(ctx.getSource(), privacyState(flags.optOutPublic())),
                ServerLang.tr(ctx.getSource(), privacyState(flags.hideCoordinates())),
                ServerLang.tr(ctx.getSource(), privacyState(flags.hidePvpOpponent())),
                ServerLang.tr(ctx.getSource(), privacyState(flags.discordOptOut())));
        return 1;
    }

    /** {@code /dc privacy [player] <flag> <on|off>} — four independent booleans. */
    private static int setPrivacy(CommandContext<CommandSourceStack> ctx, @Nullable String playerName,
                                  String flagToken, String stateToken) {
        MinecraftServer server = server(ctx);
        PrivacyTarget target = privacyTarget(ctx, playerName);
        if (server == null || target == null) {
            return 0;
        }
        PlayerPrivacy.Flag flag = PlayerPrivacy.Flag.parse(flagToken);
        Boolean state = PlayerPrivacy.parseState(stateToken);
        if (flag == null || state == null) {
            fail(ctx, "command.deathcensus.privacy.invalid");
            return 0;
        }
        CensusData data = CensusData.get(server);
        CensusData.PlayerCensus pc = data.player(target.uuid());
        switch (flag) {
            case FULL -> pc.optOutPublic = state;
            case COORDS -> pc.hideCoordinates = state;
            case OPPONENT -> pc.hidePvpOpponent = state;
            case DISCORD -> pc.discordOptOut = state;
        }
        data.setDirty();
        if (playerName != null && !playerName.isEmpty()) {
            send(ctx, "command.deathcensus.privacy.other.done", target.name(), flag.token(),
                    ServerLang.tr(ctx.getSource(), privacyState(state)));
        } else if (flag == PlayerPrivacy.Flag.FULL) {
            send(ctx, state ? "command.deathcensus.optout.done" : "command.deathcensus.optin.done");
        } else {
            send(ctx, "command.deathcensus" + ".privacy." + flag.token()
                    + (state ? ".on" : ".off"));
        }
        return 1;
    }

    private static int setOptOut(CommandContext<CommandSourceStack> ctx, boolean optOut) {
        MinecraftServer server = server(ctx);
        ServerPlayer self = ctx.getSource().getPlayer();
        if (server == null || self == null) {
            fail(ctx, "command.deathcensus.players_only");
            return 0;
        }
        CensusData data = CensusData.get(server);
        CensusData.PlayerCensus pc = data.player(self.getUUID());
        pc.optOutPublic = optOut;
        data.setDirty();
        send(ctx, optOut ? "command.deathcensus.optout.done" : "command.deathcensus.optin.done");
        return 1;
    }

    private static int export(CommandContext<CommandSourceStack> ctx, String format, @Nullable String playerName,
                              boolean anonymize) {
        MinecraftServer server = server(ctx);
        if (server == null) {
            return 0;
        }
        UUID only = null;
        if (playerName != null) {
            only = resolvePlayer(server, playerName);
            if (only == null) {
                fail(ctx, "command.deathcensus.player_not_found", playerName);
                return 0;
            }
        }
        if (only != null) {
            CensusData.PlayerCensus target = CensusData.get(server).playerOrNull(only);
            if (target != null && target.optOutPublic && !Config.includeOptedOutInExports()) {
                fail(ctx, "command.deathcensus.export.opted_out");
                return 0;
            }
        }
        Path file = CensusExport.writeExport(server, format, only, anonymize);
        if (file == null) {
            fail(ctx, "command.deathcensus.export.failed");
            return 0;
        }
        send(ctx, anonymize ? "command.deathcensus.export.anonymized" : "command.deathcensus.export.done",
                file.toString());
        return 1;
    }

    private static int backup(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = server(ctx);
        if (server == null) {
            return 0;
        }
        Path file = CensusExport.writeBackup(server);
        if (file == null) {
            fail(ctx, "command.deathcensus.export.failed");
            return 0;
        }
        send(ctx, "command.deathcensus.backup.done", file.toString());
        return 1;
    }

    private static int restore(CommandContext<CommandSourceStack> ctx, String fileName) {
        MinecraftServer server = server(ctx);
        if (server == null) {
            return 0;
        }
        int restored = CensusExport.restore(server, fileName);
        if (restored < 0) {
            fail(ctx, "command.deathcensus.restore.failed", fileName);
            return 0;
        }
        send(ctx, "command.deathcensus.restore.done", restored);
        return 1;
    }

    private static int discordTest(CommandContext<CommandSourceStack> ctx, boolean digest) {
        WebhookChannel channel = digest ? WebhookChannel.DIGEST : WebhookChannel.REALTIME;
        if (DiscordNotifier.sendTest(channel)) {
            send(ctx, "command.deathcensus.discordtest.sent");
            return 1;
        }
        fail(ctx, "command.deathcensus.discordtest.not_configured");
        return 0;
    }

    /** Builds the season award data from public snapshots and in-range deaths. */
    private static SeasonAwards seasonAwards(CensusData data, List<PlayerDeaths> snapshot,
                                              List<PlayerDeaths> inRange) {
        List<String> completers = new ArrayList<>();
        for (PlayerDeaths pd : snapshot) {
            if (pd.optOutPublic() || pd.name().isEmpty()) {
                continue;
            }
            CensusData.PlayerCensus pc;
            try {
                pc = data.playerOrNull(UUID.fromString(pd.uuid()));
            } catch (IllegalArgumentException badUuid) {
                continue;
            }
            if (pc != null && pc.lifetimeCategories().containsAll(CauseClassifier.CATEGORIES)) {
                completers.add(pd.name());
            }
        }
        DeathEntry longest = CensusStats.leaderboardSurvival(inRange, false).stream()
                .findFirst().orElse(null);
        String rarest = CensusStats.rarestCause(inRange, true).orElse("");
        return new SeasonAwards(completers, longest, rarest);
    }

    /** Season section labels come from the Discord language, not the client language. */
    private static SeasonLabels seasonLabels(String discordLanguage) {
        return new SeasonLabels(
                ServerLang.formatForLang(discordLanguage, "discord.deathcensus.season.header"),
                ServerLang.formatForLang(discordLanguage, "discord.deathcensus.season.header_field"),
                ServerLang.formatForLang(discordLanguage, "discord.deathcensus.season.last_words"),
                ServerLang.formatForLang(discordLanguage, "discord.deathcensus.season.awards"),
                ServerLang.formatForLang(discordLanguage, "discord.deathcensus.season.award.all_categories"),
                ServerLang.formatForLang(discordLanguage, "discord.deathcensus.season.award.longest_survival"),
                ServerLang.formatForLang(discordLanguage, "discord.deathcensus.season.award.rarest_cause"),
                ServerLang.formatForLang(discordLanguage, "discord.deathcensus.season.none"));
    }

    /**
     * Manual digest for an arbitrary period (§5). Read-only: it never calls
     * {@code setLastDigestEpochMillis} or touches the periodic digest throttle,
     * and it does not apply the realtime §3 notification filters.
     */
    private static int digestSeason(CommandContext<CommandSourceStack> ctx,
                                    String from, String to, boolean withHtml) {
        MinecraftServer server = server(ctx);
        if (server == null) {
            return 0;
        }
        ZoneId zone = Config.displayTimeZone();
        SeasonRange.ParseResult parsed = SeasonRange.parse(from, to, zone);
        if (!parsed.valid()) {
            fail(ctx, "command.deathcensus.digest.season.bad_range", parsed.failure().name());
            return 0;
        }
        SeasonRange range = parsed.range();
        CensusData data = CensusData.get(server);
        List<PlayerDeaths> snapshot = data.snapshot();
        List<PlayerDeaths> inRange = CensusStats.filterRange(
                snapshot, range.fromMillisInclusive(), range.toMillisInclusive());
        if (CensusStats.totalDeaths(inRange) == 0) {
            send(ctx, "command.deathcensus.digest.season.empty");
            return 0;
        }

        String lang = Config.discordLanguage();
        DiscordLogic.DigestLabels digestLabels = DiscordLogic.labelsFor(lang);
        SeasonLabels seasonLabels = seasonLabels(lang);
        SeasonAwards awards = seasonAwards(data, snapshot, inRange);
        if (Config.awardsEnabled() && Config.awardsInDigest()) {
            List<PlayerDeaths> publicInRange = new ArrayList<>();
            for (PlayerDeaths player : inRange) {
                if (!player.optOutPublic()) {
                    publicInRange.add(player);
                }
            }
            List<String> lines = awardLines(computeAwards(server, publicInRange, true), lang);
            awards = awards.withAwards(lines,
                    ServerLang.formatForLang(lang, "discord.deathcensus.awards.section"));
        }
        List<SeasonSection> sections = DiscordLogic.seasonSections(
                snapshot, range, zone, digestLabels, awards, seasonLabels);
        String content = DiscordLogic.seasonContent(
                snapshot, range, zone, digestLabels, awards, seasonLabels);

        Path html = withHtml ? CensusExport.writeSeasonHtml(server, range) : null;
        DiscordNotifier.MessageCallback onFailure = null;
        if (withHtml && html != null) {
            Path localReport = html;
            onFailure = new DiscordNotifier.MessageCallback() {
                @Override
                public void onAttachmentFailure() {
                    MinecraftServer current = ctx.getSource().getServer();
                    if (current != null) {
                        // Worker callback: hop back onto the tick thread before chatting.
                        current.execute(() -> fail(ctx,
                                "command.deathcensus.digest.season.attachment_failed", localReport.toString()));
                    }
                }

                @Override
                public void onAttachmentSuccess() {
                    MinecraftServer current = ctx.getSource().getServer();
                    if (current != null) {
                        // M11: only now is the attachment confirmed delivered.
                        current.execute(() -> send(ctx,
                                "command.deathcensus.digest.season.attached", localReport.toString()));
                    }
                }
            };
        }
        DiscordNotifier.SeasonPostResult result =
                DiscordNotifier.postSeason(WebhookChannel.DIGEST, content, sections, html, onFailure);
        switch (result) {
            case NO_CHANNEL -> {
                fail(ctx, "command.deathcensus.discordtest.not_configured");
                return 0;
            }
            case QUEUED -> {
                // M11: queued, not attached. The worker callback reports the
                // final attached/content-only outcome; digest.season.done below
                // is the command-time "queued" message.
            }
            case ATTACHMENT_TOO_LARGE -> {
                if (withHtml && html == null) {
                    // The file could not be written at all; there is no local path to report.
                    send(ctx, "command.deathcensus.digest.season.attachment_failed", "-");
                }
            }
            case CONTENT_ONLY -> {
                if (withHtml && html == null) {
                    send(ctx, "command.deathcensus.digest.season.attachment_failed", "-");
                }
            }
            default -> {
                // No other outcomes today.
            }
        }
        send(ctx, "command.deathcensus.digest.season.done");
        return 1;
    }

    public static final int DELETE_OK = 1;
    public static final int DELETE_BACKUP_FAILED = 0;
    public static final int DELETE_NOT_FOUND = -1;

    /**
     * The single backup-first delete flow used by {@code /dc delete} and by the
     * UI delete action. A negative {@code zeroBased} with an epoch resolves the
     * record by {@code (uuid, epochMillis)} at execution time, never from a
     * client-supplied index.
     */
    public static int deleteDeathAfterBackup(MinecraftServer server, UUID uuid,
                                             int zeroBased, long epochMillis) {
        if (server == null || uuid == null) {
            return DELETE_NOT_FOUND;
        }
        if (CensusExport.writeBackup(server) == null) {
            return DELETE_BACKUP_FAILED;
        }
        CensusData.PlayerCensus pc = CensusData.get(server).playerOrNull(uuid);
        int index = zeroBased;
        if (index < 0 && pc != null) {
            index = -1;
            for (int i = 0; i < pc.deaths.size(); i++) {
                if (pc.deaths.get(i).epochMillis() == epochMillis) {
                    index = i;
                    break;
                }
            }
        }
        if (pc == null || index < 0 || index >= pc.deaths.size()) {
            return DELETE_NOT_FOUND;
        }
        return CensusData.get(server).removeDeath(uuid, index) ? DELETE_OK : DELETE_NOT_FOUND;
    }

    private static int delete(CommandContext<CommandSourceStack> ctx, String playerName, int index) {
        MinecraftServer server = server(ctx);
        if (server == null) {
            return 0;
        }
        UUID uuid = resolvePlayer(server, playerName);
        if (uuid == null) {
            fail(ctx, "command.deathcensus.player_not_found", playerName);
            return 0;
        }
        CensusData.PlayerCensus pc = CensusData.get(server).playerOrNull(uuid);
        int zeroBased = index - 1;
        DeathEntry removed = pc != null && zeroBased >= 0 && zeroBased < pc.deaths.size()
                ? pc.deaths.get(zeroBased)
                : null;
        int status = deleteDeathAfterBackup(server, uuid, zeroBased, -1L);
        if (status == DELETE_BACKUP_FAILED) {
            fail(ctx, "command.deathcensus.delete.backup_failed");
            return 0;
        }
        if (status != DELETE_OK || removed == null) {
            fail(ctx, "command.deathcensus.delete.not_found", playerName, index);
            return 0;
        }
        String date = TimeFormat.formatDateTime(removed.epochMillis(), Config.displayTimeZone());
        send(ctx, "command.deathcensus.delete.done", playerName, index, date);
        return 1;
    }

    private static int resetPlayer(CommandContext<CommandSourceStack> ctx, String name) {
        MinecraftServer server = server(ctx);
        if (server == null) {
            return 0;
        }
        UUID uuid = resolvePlayer(server, name);
        if (uuid == null) {
            fail(ctx, "command.deathcensus.player_not_found", name);
            return 0;
        }
        if (CensusExport.writeBackup(server) == null) {
            fail(ctx, "command.deathcensus.delete.backup_failed");
            return 0;
        }
        if (!CensusData.get(server).removePlayer(uuid)) {
            fail(ctx, "command.deathcensus.player_not_found", name);
            return 0;
        }
        send(ctx, "command.deathcensus.reset.player_done", name);
        return 1;
    }

    private static int resetAll(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = server(ctx);
        if (server == null) {
            return 0;
        }
        if (CensusExport.writeBackup(server) == null) {
            fail(ctx, "command.deathcensus.delete.backup_failed");
            return 0;
        }
        CensusData.get(server).replaceAll(List.of());
        send(ctx, "command.deathcensus.reset.all_done");
        return 1;
    }

    /** Maps a resolved webhook value to a status word; the value itself never escapes. */
    static String webhookStatus(String resolved) {
        if (resolved == null || resolved.isEmpty()) {
            return "not configured";
        }
        return DiscordLogic.isValidWebhookUrl(resolved) ? "configured" : "invalid URL";
    }

    private static int doctor(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = server(ctx);
        if (server == null) {
            return 0;
        }
        CensusData data = CensusData.get(server);
        int players = 0;
        int optedOut = 0;
        int total = 0;
        long oldest = Long.MAX_VALUE;
        long newest = Long.MIN_VALUE;
        for (CensusData.PlayerCensus pc : data.allPlayers().values()) {
            players++;
            if (pc.optOutPublic) {
                optedOut++;
            }
            for (DeathEntry e : pc.deaths) {
                total++;
                oldest = Math.min(oldest, e.epochMillis());
                newest = Math.max(newest, e.epochMillis());
            }
        }
        ZoneId zone = Config.displayTimeZone();
        String oldestText = total > 0 ? TimeFormat.formatDateTime(oldest, zone) : "-";
        String newestText = total > 0 ? TimeFormat.formatDateTime(newest, zone) : "-";
        String primary = Config.discordWebhookUrl();
        String digestUrl = Config.discordDigestWebhookUrl();
        String webhookStatus = webhookStatus(
                DiscordLogic.resolveWebhookUrl(WebhookChannel.REALTIME, primary, digestUrl));
        String digestWebhookStatus = webhookStatus(
                DiscordLogic.resolveWebhookUrl(WebhookChannel.DIGEST, primary, digestUrl));
        long lastDigest = data.lastDigestEpochMillis();
        String digestText = lastDigest > 0L ? TimeFormat.formatDateTime(lastDigest, zone) : "never";
        List<String> backups = CensusExport.listBackups(server);
        String newestBackup = backups.isEmpty() ? "-" : backups.get(backups.size() - 1);

        send(ctx, "command.deathcensus.doctor.header");
        sendLiteral(ctx, "Players: " + players + " (opted out: " + optedOut + ")");
        sendLiteral(ctx, "Total deaths: " + total);
        sendLiteral(ctx, "Vanilla baseline: " + data.vanillaBaselineTotal() + " pre-install death(s)");
        sendLiteral(ctx, "Oldest death: " + oldestText);
        sendLiteral(ctx, "Newest death: " + newestText);
        sendLiteral(ctx, "Storage: " + CensusData.DATA_NAME);
        sendLiteral(ctx, "Discord webhook: " + webhookStatus);
        sendLiteral(ctx, "Discord digest webhook: " + digestWebhookStatus
                + (DiscordNotifier.digestWarningSilenced() ? " (invalid config reported once)" : ""));
        sendLiteral(ctx, "Discord retry: " + Config.discordRetryMaxAttempts() + " attempts / "
                + Config.discordRetryBaseDelayMillis() + " ms base");
        List<String> realtimeDimensions = Config.discordNotifyDimensions();
        sendLiteral(ctx, "Discord realtime dimensions: "
                + (realtimeDimensions.isEmpty() ? "all" : String.join(", ", realtimeDimensions)));
        sendLiteral(ctx, "Discord realtime PvP mode: " + Config.discordNotifyPvpMode().configId());
        sendLiteral(ctx, "Last digest: " + digestText);
        sendLiteral(ctx, "Season digest: /dc digest season <from> <to> [html]");
        sendLiteral(ctx, "Backups: " + backups.size() + " (newest: " + newestBackup + ")");
        sendLiteral(ctx, "Languages: " + ServerLang.BUNDLED_LANGUAGES.size()
                + ", en keys: " + ServerLang.fallbacks().size());
        // §10: node integration is deferred (NeoForge has no built-in node API).
        // Status words only — no webhook URL or other secret is ever printed.
        sendLiteral(ctx, "Permissions: OP level 2 (node integration deferred)");
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = server(ctx);
        if (server != null) {
            // M10: config reload is a new report cycle for an invalid digest URL.
            DiscordNotifier.resetDigestUrlWarning();
            DiscordNotifier.checkAndSendDigest(server);
        }
        send(ctx, "command.deathcensus.reload.done");
        return 1;
    }
}
