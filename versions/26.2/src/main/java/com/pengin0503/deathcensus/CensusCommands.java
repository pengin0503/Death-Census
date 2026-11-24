package com.pengin0503.deathcensus;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /deathcensus (alias /dc) — personal history, server statistics,
 * leaderboards, privacy opt-out and operator tools. Chat output only.
 */
public final class CensusCommands {
    private static final int PAGE_SIZE = 10;

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

        root.then(literal("history")
                .executes(ctx -> history(ctx, null, 1))
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, Integer>argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> history(ctx, null, IntegerArgumentType.getInteger(ctx, "page"))))
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, String>argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(playerNames(ctx), builder))
                        .executes(ctx -> history(ctx, StringArgumentType.getString(ctx, "player"), 1))
                        .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                                .<CommandSourceStack, Integer>argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> history(ctx, StringArgumentType.getString(ctx, "player"),
                                        IntegerArgumentType.getInteger(ctx, "page"))))));

        root.then(literal("stats")
                .executes(CensusCommands::ownStats)
                .then(literal("server").executes(CensusCommands::serverStats)));

        LiteralArgumentBuilder<CommandSourceStack> leaderboard = literal("leaderboard");
        for (String type : List.of("deaths", "survival", "fastest", "rarest")) {
            leaderboard.then(literal(type)
                    .executes(ctx -> leaderboard(ctx, type, 1))
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                            .<CommandSourceStack, Integer>argument("page", IntegerArgumentType.integer(1))
                            .executes(ctx -> leaderboard(ctx, type, IntegerArgumentType.getInteger(ctx, "page")))));
        }
        root.then(leaderboard);

        root.then(literal("optout").executes(ctx -> setOptOut(ctx, true)));
        root.then(literal("optin").executes(ctx -> setOptOut(ctx, false)));

        root.then(literal("lookup")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, String>argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(playerNames(ctx), builder))
                        .executes(ctx -> lookup(ctx, StringArgumentType.getString(ctx, "player"), 1))
                        .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                                .<CommandSourceStack, Integer>argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> lookup(ctx, StringArgumentType.getString(ctx, "player"),
                                        IntegerArgumentType.getInteger(ctx, "page"))))));

        LiteralArgumentBuilder<CommandSourceStack> export = literal("export").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
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
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(CensusCommands::backup));

        root.then(literal("restore")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, String>argument("file", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(backupNames(ctx), builder))
                        .executes(ctx -> restore(ctx, StringArgumentType.getString(ctx, "file")))));

        root.then(literal("discordtest")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(CensusCommands::discordTest));

        root.then(literal("reload")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(CensusCommands::reload));

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
            names.add(p.getGameProfile().name());
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
            String onlineName = online.getGameProfile().name();
            if (onlineName != null && onlineName.equalsIgnoreCase(name)) {
                return online.getUUID();
            }
        }
        return CensusData.get(server).findPlayerByName(name);
    }

    private static boolean isAdmin(CommandContext<CommandSourceStack> ctx) {
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(ctx.getSource());
    }

    private static String formatEntryLine(DeathEntry e, int index) {
        return "#" + index
                + " · Day " + (e.gameDay() + 1)
                + " · " + TimeFormat.formatDateTime(e.epochMillis(), Config.displayTimeZone())
                + " · " + e.dimension()
                + " (" + (int) Math.floor(e.x()) + ", " + (int) Math.floor(e.y()) + ", " + (int) Math.floor(e.z()) + ")"
                + " · " + (e.deathMessage().isEmpty() ? e.causeCategory() : e.deathMessage())
                + " · survived " + TimeFormat.formatDuration(e.survivalTicks());
    }

    private static int showHistory(CommandContext<CommandSourceStack> ctx, UUID uuid, String displayName, int page) {
        MinecraftServer server = server(ctx);
        CensusData.PlayerCensus pc = CensusData.get(server).playerOrNull(uuid);
        if (pc == null || pc.deaths.isEmpty()) {
            send(ctx, "command.deathcensus.history.empty", displayName);
            return 0;
        }
        List<DeathEntry> newestFirst = new ArrayList<>(pc.deaths);
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
        send(ctx, "command.deathcensus.help.history");
        send(ctx, "command.deathcensus.help.stats");
        send(ctx, "command.deathcensus.help.leaderboard");
        send(ctx, "command.deathcensus.help.optout");
        if (isAdmin(ctx)) {
            send(ctx, "command.deathcensus.help.admin");
        }
        return 1;
    }

    private static int history(CommandContext<CommandSourceStack> ctx, @Nullable String playerName, int page) {
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
            return showHistory(ctx, self.getUUID(), self.getGameProfile().name(), page);
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
        return showHistory(ctx, target, playerName, page);
    }

    private static int lookup(CommandContext<CommandSourceStack> ctx, String playerName, int page) {
        MinecraftServer server = server(ctx);
        if (server == null) {
            return 0;
        }
        UUID target = resolvePlayer(server, playerName);
        if (target == null) {
            fail(ctx, "command.deathcensus.player_not_found", playerName);
            return 0;
        }
        return showHistory(ctx, target, playerName, page);
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
            send(ctx, "command.deathcensus.history.empty", self.getGameProfile().name());
            return 0;
        }
        PlayerDeaths selfDeaths = new PlayerDeaths(self.getUUID().toString(),
                self.getGameProfile().name(), pc.optOutPublic, pc.deaths);
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
        MinecraftServer server = server(ctx);
        if (server == null) {
            return 0;
        }
        List<PlayerDeaths> all = CensusData.get(server).snapshot();
        int total = CensusStats.totalDeaths(all);
        send(ctx, "command.deathcensus.server_stats.header");
        send(ctx, "command.deathcensus.stats.deaths", total);
        for (Map.Entry<String, Integer> e : CensusStats.causeCounts(all, false)) {
            int pct = total > 0 ? (int) Math.round(100.0 * e.getValue() / total) : 0;
            sendLiteral(ctx, "· " + e.getKey() + ": " + e.getValue() + " (" + pct + "%)");
        }
        List<CensusStats.PlayerSummary> board = CensusStats.leaderboardDeaths(all, false);
        if (!board.isEmpty()) {
            send(ctx, "command.deathcensus.server_stats.top_player", board.get(0).name(), board.get(0).deaths());
        }
        CensusStats.rarestCause(all, false)
                .ifPresent(cause -> send(ctx, "command.deathcensus.server_stats.rarest", cause));
        return 1;
    }

    private static int leaderboard(CommandContext<CommandSourceStack> ctx, String type, int page) {
        MinecraftServer server = server(ctx);
        if (server == null) {
            return 0;
        }
        List<PlayerDeaths> all = CensusData.get(server).snapshot();
        send(ctx, "command.deathcensus.leaderboard.header", type);
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
                java.util.Optional<String> rarest = CensusStats.rarestCause(all, false);
                if (rarest.isEmpty()) {
                    send(ctx, "command.deathcensus.leaderboard.empty");
                    return 0;
                }
                send(ctx, "command.deathcensus.leaderboard.rarest_cause", rarest.get());
                List<DeathEntry> entries = CensusStats.deathsWithCause(all, rarest.get(), false);
                Pagination.Page<DeathEntry> p = Pagination.of(entries, page, PAGE_SIZE);
                for (DeathEntry e : p.items()) {
                    sendLiteral(ctx, "· " + e.playerName() + " — Day " + (e.gameDay() + 1)
                            + " · " + TimeFormat.formatDateTime(e.epochMillis(), Config.displayTimeZone()));
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

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = server(ctx);
        if (server != null) {
            DiscordNotifier.checkAndSendDigest(server);
        }
        send(ctx, "command.deathcensus.reload.done");
        return 1;
    }
}
