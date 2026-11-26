package com.pengin0503.deathcensus;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
                .executes(CensusCommands::help)
                .redirect(dispatcher.getRoot().getChild("deathcensus")));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root() {
        LiteralArgumentBuilder<CommandSourceStack> root = literal("deathcensus")
                .executes(CensusCommands::help);

        root.then(literal("help").executes(CensusCommands::help));

        root.then(literal("excuse")
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, String>argument("text", StringArgumentType.greedyString())
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

        root.then(literal("lookup")
                .requires(src -> src.hasPermission(2))
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

        LiteralArgumentBuilder<CommandSourceStack> export = literal("export").requires(src -> src.hasPermission(2));
        for (String format : List.of("html", "json", "csv")) {
            export.then(literal(format)
                    .executes(ctx -> export(ctx, format, null))
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                            .<CommandSourceStack, String>argument("player", StringArgumentType.word())
                            .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(playerNames(ctx), builder))
                            .executes(ctx -> export(ctx, format, StringArgumentType.getString(ctx, "player")))));
        }
        root.then(export);

        root.then(literal("backup")
                .requires(src -> src.hasPermission(2))
                .executes(CensusCommands::backup));

        root.then(literal("restore")
                .requires(src -> src.hasPermission(2))
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, String>argument("file", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(backupNames(ctx), builder))
                        .executes(ctx -> restore(ctx, StringArgumentType.getString(ctx, "file")))));

        root.then(literal("discordtest")
                .requires(src -> src.hasPermission(2))
                .executes(CensusCommands::discordTest));

        root.then(literal("reload")
                .requires(src -> src.hasPermission(2))
                .executes(CensusCommands::reload));

        root.then(literal("delete")
                .requires(src -> src.hasPermission(2))
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, String>argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(playerNames(ctx), builder))
                        .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                                .<CommandSourceStack, Integer>argument("index", IntegerArgumentType.integer(1))
                                .executes(ctx -> delete(ctx, StringArgumentType.getString(ctx, "player"),
                                        IntegerArgumentType.getInteger(ctx, "index"))))));

        LiteralArgumentBuilder<CommandSourceStack> reset = literal("reset").requires(src -> src.hasPermission(2));
        reset.then(literal("player")
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, String>argument("name", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(playerNames(ctx), builder))
                        .executes(ctx -> resetPlayer(ctx, StringArgumentType.getString(ctx, "name")))));
        reset.then(literal("all").then(literal("confirm").executes(CensusCommands::resetAll)));
        root.then(reset);

        root.then(literal("doctor")
                .requires(src -> src.hasPermission(2))
                .executes(CensusCommands::doctor));

        return root;
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
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            names.add(p.getGameProfile().getName());
        }
        for (CensusData.PlayerCensus pc : CensusData.get(server).allPlayers().values()) {
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
        return ctx.getSource().hasPermission(2);
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

    private static String formatEntryLine(DeathEntry e, int index) {
        return "#" + index
                + " · Day " + (e.gameDay() + 1)
                + " · " + TimeFormat.formatDateTime(e.epochMillis(), Config.displayTimeZone())
                + " · " + e.dimension()
                + " (" + (int) Math.floor(e.x()) + ", " + (int) Math.floor(e.y()) + ", " + (int) Math.floor(e.z()) + ")"
                + " · " + (e.deathMessage().isEmpty() ? e.causeCategory() : e.deathMessage())
                + " · survived " + TimeFormat.formatDuration(e.survivalTicks())
                + (e.excuse().isEmpty() ? "" : " · \"" + e.excuse() + "\"")
                + (e.lastWords().isEmpty() ? "" : " · last words: \"" + e.lastWords() + "\"")
                + (e.fCount() > 0 ? " · F x" + e.fCount() : "");
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
        int base = (p.page() - 1) * PAGE_SIZE;
        for (int i = 0; i < p.items().size(); i++) {
            sendLiteral(ctx, formatEntryLine(p.items().get(i), p.total() - (base + i)));
        }
        if (p.pageCount() > 1) {
            send(ctx, "command.deathcensus.page.hint", p.page(), p.pageCount());
        }
        return 1;
    }

    // ------------------------------------------------------------ commands

    private static int help(CommandContext<CommandSourceStack> ctx) {
        send(ctx, "command.deathcensus.help.title");
        send(ctx, "command.deathcensus.help.excuse");
        send(ctx, "command.deathcensus.help.history");
        send(ctx, "command.deathcensus.help.stats");
        send(ctx, "command.deathcensus.help.leaderboard");
        send(ctx, "command.deathcensus.help.optout");
        if (isAdmin(ctx)) {
            send(ctx, "command.deathcensus.help.admin");
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
                self.getGameProfile().getName(), pc.optOutPublic, pc.deaths);
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
        List<PlayerDeaths> all = CensusStats.filterSince(CensusData.get(server).snapshot(), sinceMillis(period));
        int total = CensusStats.totalDeaths(all);
        send(ctx, "command.deathcensus.server_stats.header");
        if (!"all".equals(period)) {
            sendLiteral(ctx, "Period: " + period);
        }
        send(ctx, "command.deathcensus.stats.deaths", total);
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

    private static int export(CommandContext<CommandSourceStack> ctx, String format, @Nullable String playerName) {
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
        Path file = CensusExport.writeExport(server, format, only);
        if (file == null) {
            fail(ctx, "command.deathcensus.export.failed");
            return 0;
        }
        send(ctx, "command.deathcensus.export.done", file.toString());
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

    private static int discordTest(CommandContext<CommandSourceStack> ctx) {
        if (DiscordNotifier.sendTest()) {
            send(ctx, "command.deathcensus.discordtest.sent");
            return 1;
        }
        fail(ctx, "command.deathcensus.discordtest.not_configured");
        return 0;
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
        if (CensusExport.writeBackup(server) == null) {
            fail(ctx, "command.deathcensus.delete.backup_failed");
            return 0;
        }
        CensusData.PlayerCensus pc = CensusData.get(server).playerOrNull(uuid);
        int zeroBased = index - 1;
        DeathEntry removed = pc != null && zeroBased >= 0 && zeroBased < pc.deaths.size()
                ? pc.deaths.get(zeroBased)
                : null;
        if (!CensusData.get(server).removeDeath(uuid, zeroBased)) {
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
        String webhook = DiscordLogic.resolveWebhookUrl(Config.discordWebhookUrl());
        String webhookStatus = webhook.isEmpty() ? "not configured"
                : (DiscordLogic.isValidWebhookUrl(webhook) ? "configured" : "invalid URL");
        long lastDigest = data.lastDigestEpochMillis();
        String digestText = lastDigest > 0L ? TimeFormat.formatDateTime(lastDigest, zone) : "never";
        List<String> backups = CensusExport.listBackups(server);
        String newestBackup = backups.isEmpty() ? "-" : backups.get(backups.size() - 1);

        send(ctx, "command.deathcensus.doctor.header");
        sendLiteral(ctx, "Players: " + players + " (opted out: " + optedOut + ")");
        sendLiteral(ctx, "Total deaths: " + total);
        sendLiteral(ctx, "Oldest death: " + oldestText);
        sendLiteral(ctx, "Newest death: " + newestText);
        sendLiteral(ctx, "Storage: " + CensusData.DATA_NAME);
        sendLiteral(ctx, "Discord webhook: " + webhookStatus);
        sendLiteral(ctx, "Last digest: " + digestText);
        sendLiteral(ctx, "Backups: " + backups.size() + " (newest: " + newestBackup + ")");
        sendLiteral(ctx, "Languages: " + ServerLang.BUNDLED_LANGUAGES.size()
                + ", en keys: " + ServerLang.fallbacks().size());
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = server(ctx);
        if (server != null) {
            DiscordNotifier.checkAndSendDigest(server);
        }
        send(ctx, "command.deathcensus.reload.done");
        return 1;
    }
}
