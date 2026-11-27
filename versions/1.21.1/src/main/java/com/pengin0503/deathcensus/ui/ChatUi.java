package com.pengin0503.deathcensus.ui;

import com.pengin0503.deathcensus.Config;
import com.pengin0503.deathcensus.DeathContext;
import com.pengin0503.deathcensus.ServerLang;
import com.pengin0503.deathcensus.TimeFormat;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 1.21.1 presentation adapter: the common view models become chat lines with
 * vanilla {@code RUN_COMMAND} / {@code SUGGEST_COMMAND} buttons. There is no
 * custom click event or dialog system in this version (DESIGN §0.4/§5), so all
 * navigation goes through the hidden {@code /dc ui ...} subtree.
 */
public final class ChatUi {
    private ChatUi() {
    }

    /** Entry point used by {@code /dc} with no arguments. */
    public static void openHome(ServerPlayer player) {
        UiResult result = UiActions.handle(new UiRequest(player.getUUID(), "home"), new ChatUiContext(player));
        render(player, "home", result);
    }

    /**
     * PR-4: one compact extra system line per online viewer after a death.
     * Gating reuses the UiPrivacy predicate; the line carries only the victim's
     * name and (uuid, epoch) targets, never coordinates or a death message.
     */
    public static void sendDeathLine(ServerPlayer viewer, UUID victim, long epoch, String victimName) {
        if (!new ChatUiContext(viewer).canViewDetail(victim)) {
            return;
        }
        String target = victim + " " + epoch;
        MutableComponent line = ServerLang.tr(viewer, "command.deathcensus.ui.deathline", victimName);
        line.append(" ").append(commandButton(viewer, "command.deathcensus.ui.deathline.details",
                "/dc ui detail " + target, "command.deathcensus.ui.tooltip.detail"));
        line.append(" ").append(commandButton(viewer, "command.deathcensus.ui.deathline.excuse",
                "/dc ui excuse " + target, "command.deathcensus.ui.tooltip.excuse"));
        line.append(" ").append(commandButton(viewer, "command.deathcensus.ui.deathline.f",
                "/dc ui respect " + target, "command.deathcensus.ui.tooltip.respect"));
        line(viewer, line);
    }

    /** The plain help text stays available alongside the chat menu on entry. */
    public static void sendHelpLines(ServerPlayer player) {
        line(player, ServerLang.tr(player, "command.deathcensus.help.title"));
        line(player, ServerLang.tr(player, "command.deathcensus.help.excuse"));
        line(player, ServerLang.tr(player, "command.deathcensus.help.history"));
        line(player, ServerLang.tr(player, "command.deathcensus.help.stats"));
        line(player, ServerLang.tr(player, "command.deathcensus.help.leaderboard"));
        line(player, ServerLang.tr(player, "command.deathcensus.help.optout"));
        line(player, ServerLang.tr(player, "command.deathcensus.help.privacy"));
        if (new ChatUiContext(player).isAdmin()) {
            line(player, ServerLang.tr(player, "command.deathcensus.help.admin"));
        }
    }

    /** Turns the arguments of a {@code /dc ui ...} command into a dispatch. */
    public static boolean dispatchArgs(ServerPlayer player, List<String> args) {
        Optional<UiRequest> parsed = ChatUiRequestParser.parse(player.getUUID(), args);
        if (parsed.isEmpty()) {
            line(player, ServerLang.tr(player, "command.deathcensus.ui.chat.invalid"));
            return false;
        }
        UiResult result = UiActions.handle(parsed.get(), new ChatUiContext(player));
        render(player, parsed.get().action(), result);
        return true;
    }

    /**
     * The chat-only excuse command reuses the dispatcher's save action with the
     * viewer's live session token, so it targets the selected death and keeps
     * the same validation and feedback as the Dialog version.
     */
    public static void saveExcuseFromChat(ServerPlayer player, UUID uuid, long epoch, String text) {
        ChatUiContext ctx = new ChatUiContext(player);
        String token = ChatUiContext.currentToken(player);
        if (token.isEmpty()) {
            UiSession session = ctx.newSession(HistoryFilter.DEFAULT, UiSession.DEFAULT_RANKING);
            token = session == null ? "" : session.token();
        }
        Map<String, String> inputs = Map.of("in_excuse", text);
        UiRequest request = new UiRequest(player.getUUID(), "excuse_save", token, 1,
                uuid.toString(), epoch, inputs);
        render(player, "excuse_save", UiActions.handle(request, ctx));
    }

    private static void render(ServerPlayer player, String requestedAction, UiResult result) {
        UiResult effective = result;
        if (result instanceof UiResult.SideEffect sideEffect) {
            // UiActions builds parameterized feedback (including delete.done
            // with name/index/date), so every side effect is rendered here.
            if (sideEffect.feedback() != null && !sideEffect.feedback().key().isEmpty()) {
                line(player, tr(player, sideEffect.feedback()));
            }
            effective = sideEffect.followUp();
        }
        if (!(effective instanceof UiResult.Open open)) {
            return;
        }
        Object view = open.view();
        if ("admin".equals(requestedAction) && view instanceof HomeView home) {
            renderAdmin(player, home);
        } else if (view instanceof HomeView home) {
            renderHome(player, home);
        } else if (view instanceof HistoryView history) {
            renderHistory(player, history);
        } else if (view instanceof DeathDetailView detail) {
            renderDetail(player, detail);
        } else if (view instanceof RankingView ranking) {
            renderRankings(player, ranking);
        } else if (view instanceof PrivacyView privacy) {
            renderPrivacy(player, privacy);
        } else if (view instanceof NoticeView notice) {
            renderNotice(player, notice);
        } else if (view instanceof ConfirmationView confirmation) {
            renderConfirmation(player, confirmation);
        }
    }

    // --------------------------------------------------------------- screens

    private static void renderHome(ServerPlayer player, HomeView view) {
        line(player, ServerLang.tr(player, "command.deathcensus.ui.home.title"));
        line(player, ServerLang.tr(player, "command.deathcensus.ui.home.total", view.totalDeaths()));
        line(player, ServerLang.tr(player, "command.deathcensus.ui.home.survival",
                TimeFormat.formatDuration(view.currentSurvivalTicks())));
        line(player, ServerLang.tr(player, "command.deathcensus.ui.home.server_total", view.serverTotalDeaths()));
        Component last = UiText.RECORD_UNAVAILABLE_KEY.equals(view.lastDeathLine())
                ? ServerLang.tr(player, UiText.RECORD_UNAVAILABLE_KEY)
                : Component.literal(truncate(view.lastDeathLine()));
        line(player, ServerLang.tr(player, "command.deathcensus.ui.home.last_death", last));
        List<Component> buttons = new ArrayList<>();
        buttons.add(commandButton(player, "command.deathcensus.ui.home.personal_stats", "/dc stats",
                "command.deathcensus.ui.tooltip.personal_stats"));
        buttons.add(commandButton(player, "command.deathcensus.ui.home.server_stats", "/dc stats server",
                "command.deathcensus.ui.tooltip.server_stats"));
        buttons.add(commandButton(player, "command.deathcensus.ui.home.rankings", "/dc ui rankings",
                "command.deathcensus.ui.tooltip.rankings"));
        buttons.add(commandButton(player, "command.deathcensus.ui.home.history", "/dc ui history",
                "command.deathcensus.ui.tooltip.history"));
        buttons.add(commandButton(player, "command.deathcensus.ui.home.privacy", "/dc ui privacy",
                "command.deathcensus.ui.tooltip.privacy"));
        if (view.admin()) {
            buttons.add(commandButton(player, "command.deathcensus.ui.home.admin", "/dc ui admin",
                    "command.deathcensus.ui.tooltip.admin"));
        }
        buttonRows(player, buttons);
    }

    private static void renderHistory(ServerPlayer player, HistoryView view) {
        HistoryFilter filter = view.filter();
        line(player, ServerLang.tr(player, "command.deathcensus.ui.history.title"));
        line(player, ServerLang.tr(player, "command.deathcensus.ui.history.header",
                view.total(), view.page(), view.pageCount()));
        line(player, ServerLang.tr(player, "command.deathcensus.ui.history.filter",
                filter.period(), filter.category(), filter.dimension(), filter.query()));
        if (view.rows().isEmpty()) {
            line(player, ServerLang.tr(player, "command.deathcensus.ui.history.empty"));
        }
        for (HistoryRow row : view.rows()) {
            line(player, Component.literal(truncate(row.displayLine()))
                    .append(" ")
                    .append(button(player, "command.deathcensus.ui.history.open",
                            "/dc ui detail " + row.targetUuid() + " " + row.epochMillis() + " " + view.sessionToken(),
                            "command.deathcensus.ui.tooltip.detail", false)));
        }
        // Chat has no dropdowns or text boxes: the filter is a short command
        // stored in the session (§5). The prefix is prefilled for the player.
        String filterPrefix = "/dc ui history filter " + filter.period() + " " + filter.category() + " ";
        List<Component> buttons = new ArrayList<>();
        buttons.add(suggestButton(player, "command.deathcensus.ui.history.search", filterPrefix,
                "command.deathcensus.ui.tooltip.search"));
        buttons.add(commandButton(player, "command.deathcensus.ui.prev", "/dc ui history "
                + Math.max(1, view.page() - 1) + " " + view.sessionToken(), null));
        buttons.add(commandButton(player, "command.deathcensus.ui.next", "/dc ui history "
                + Math.min(Math.max(1, view.pageCount()), view.page() + 1) + " " + view.sessionToken(), null));
        buttons.add(backButton(player));
        buttonRows(player, buttons);
        line(player, ServerLang.tr(player, "command.deathcensus.ui.chat.filter_hint"));
    }

    /** PR-3 context lines in the chat detail view (DESIGN 1.4.0 §11). */
    private static void appendContextLines(ServerPlayer player, DeathContext context) {
        if (context == null || context.isEmpty()) {
            line(player, ServerLang.tr(player, "command.deathcensus.ui.detail.context.empty"));
            return;
        }
        if (!context.mainHand().isEmpty()) {
            line(player, ServerLang.tr(player, "command.deathcensus.ui.detail.context.main_hand",
                    context.mainHand()));
        }
        if (!context.armor().isEmpty()) {
            line(player, ServerLang.tr(player, "command.deathcensus.ui.detail.context.armor",
                    String.join(", ", context.armor())));
        }
        if (!context.effects().isEmpty()) {
            line(player, ServerLang.tr(player, "command.deathcensus.ui.detail.context.effects",
                    String.join(", ", context.effects())));
        }
        if (!context.biome().isEmpty()) {
            line(player, ServerLang.tr(player, "command.deathcensus.ui.detail.context.biome",
                    context.biome()));
        }
        if (!context.weather().isEmpty()) {
            line(player, ServerLang.tr(player, "command.deathcensus.ui.detail.context.weather",
                    context.weather()));
        }
    }

    private static void renderDetail(ServerPlayer player, DeathDetailView view) {
        line(player, ServerLang.tr(player, "command.deathcensus.ui.detail.title"));
        line(player, ServerLang.tr(player, "command.deathcensus.ui.detail.date", view.dateTime()));
        line(player, view.coordsHidden()
                ? ServerLang.tr(player, "command.deathcensus.ui.detail.coords_hidden", view.dimension())
                : ServerLang.tr(player, "command.deathcensus.ui.detail.coords",
                        view.x(), view.y(), view.z(), view.dimension()));
        line(player, ServerLang.tr(player, "command.deathcensus.ui.detail.cause",
                view.causeCategory(), truncate(view.deathMessage())));
        line(player, ServerLang.tr(player, "command.deathcensus.ui.detail.killer",
                view.killerName().isEmpty()
                        ? ServerLang.tr(player, "command.deathcensus.ui.none") : view.killerName()));
        line(player, ServerLang.tr(player, "command.deathcensus.ui.detail.survival",
                TimeFormat.formatDuration((int) Math.max(0L,
                        Math.min(view.survivalTicks(), Integer.MAX_VALUE)))));
        line(player, ServerLang.tr(player, "command.deathcensus.ui.detail.xp", view.experienceLevel()));
        line(player, ServerLang.tr(player, "command.deathcensus.ui.detail.last_words",
                view.lastWords().isEmpty()
                        ? ServerLang.tr(player, "command.deathcensus.ui.none") : truncate(view.lastWords())));
        line(player, ServerLang.tr(player, "command.deathcensus.ui.detail.excuse",
                view.excuse().isEmpty()
                        ? ServerLang.tr(player, "command.deathcensus.ui.none") : truncate(view.excuse())));
        line(player, ServerLang.tr(player, "command.deathcensus.ui.detail.f_count", view.fCount()));
        line(player, ServerLang.tr(player, "command.deathcensus.ui.detail.witnesses",
                view.witnesses().isEmpty()
                        ? ServerLang.tr(player, "command.deathcensus.ui.none")
                        : String.join(", ", view.witnesses())));
        appendContextLines(player, view.context());

        String token = ChatUiContext.currentToken(player);
        String target = view.targetUuid() + " " + view.epochMillis();
        List<Component> buttons = new ArrayList<>();
        if (view.canEditExcuse()) {
            buttons.add(commandButton(player, "command.deathcensus.ui.detail.excuse_button",
                    "/dc ui excuse " + target + " " + token,
                    "command.deathcensus.ui.tooltip.excuse"));
        }
        buttons.add(commandButton(player, "command.deathcensus.ui.detail.respect",
                "/dc ui respect " + target + " " + token,
                "command.deathcensus.ui.tooltip.respect"));
        buttons.add(commandButton(player, "command.deathcensus.ui.detail.history", "/dc ui history", null));
        if (view.canDelete()) {
            buttons.add(commandButton(player, "command.deathcensus.ui.detail.delete",
                    "/dc ui delete " + target + " " + token,
                    "command.deathcensus.ui.tooltip.delete"));
        }
        buttons.add(backButton(player));
        buttonRows(player, buttons);
    }

    private static void renderRankings(ServerPlayer player, RankingView view) {
        String language = player.clientInformation() != null
                ? player.clientInformation().language() : ServerLang.DEFAULT_LANG;
        String typeLabel = switch (view.type()) {
            case "awards" -> ServerLang.formatForLang(language, "command.deathcensus.ui.ranking.awards");
            case "chronicle" -> ServerLang.formatForLang(language, "command.deathcensus.ui.ranking.chronicle");
            case "nemesis" -> ServerLang.formatForLang(language, "command.deathcensus.ui.ranking.nemesis");
            case "hotspots" -> ServerLang.formatForLang(language, "command.deathcensus.ui.ranking.hotspots");
            default -> view.type();
        };
        line(player, ServerLang.tr(player, "command.deathcensus.ui.rankings.title"));
        line(player, ServerLang.tr(player, "command.deathcensus.ui.rankings.header",
                typeLabel, view.period(), view.page(), view.pageCount()));
        if (view.rows().isEmpty()) {
            line(player, ServerLang.tr(player, "command.deathcensus.ui.rankings.empty"));
        }
        for (String row : view.rows()) {
            line(player, Component.literal(truncate(row)));
        }
        if (!view.anonymousTotalsLine().isEmpty()) {
            line(player, ServerLang.tr(player, "command.deathcensus.ui.rankings.footer",
                    truncate(view.anonymousTotalsLine())));
        }
        String token = ChatUiContext.currentToken(player);
        List<Component> buttons = new ArrayList<>();
        buttons.add(suggestButton(player, "command.deathcensus.ui.rankings.apply",
                "/dc ui rankings " + view.type() + " " + view.period() + " ",
                "command.deathcensus.ui.tooltip.apply"));
        buttons.add(commandButton(player, "command.deathcensus.ui.prev", "/dc ui rankings page "
                + Math.max(1, view.page() - 1) + " " + token, null));
        buttons.add(commandButton(player, "command.deathcensus.ui.next", "/dc ui rankings page "
                + Math.min(Math.max(1, view.pageCount()), view.page() + 1) + " " + token, null));
        buttons.add(backButton(player));
        buttonRows(player, buttons);
        line(player, ServerLang.tr(player, Config.awardsEnabled()
                ? "command.deathcensus.ui.chat.ranking_hint"
                : "command.deathcensus.ui.chat.ranking_hint_no_awards"));
    }

    private static void renderPrivacy(ServerPlayer player, PrivacyView view) {
        line(player, ServerLang.tr(player, "command.deathcensus.ui.privacy.title"));
        line(player, privacyStateLine(player, "command.deathcensus.ui.privacy.full", view.optOut()));
        line(player, privacyStateLine(player, "command.deathcensus.ui.privacy.coords", view.hideCoordinates()));
        line(player, privacyStateLine(player, "command.deathcensus.ui.privacy.opponent", view.hidePvpOpponent()));
        line(player, privacyStateLine(player, "command.deathcensus.ui.privacy.discord", view.discordOptOut()));
        line(player, tr(player, view.explanation()));
        String token = ChatUiContext.currentToken(player);
        String command = "/dc ui privacy ";
        buttonRows(player, List.of(
                commandButton(player, "command.deathcensus.ui.input.optout",
                        command + "full " + (view.optOut() ? "off" : "on") + " " + token, null),
                commandButton(player, "command.deathcensus.ui.input.hide_coords",
                        command + "coords " + (view.hideCoordinates() ? "off" : "on") + " " + token, null),
                commandButton(player, "command.deathcensus.ui.input.hide_opponent",
                        command + "opponent " + (view.hidePvpOpponent() ? "off" : "on") + " " + token, null),
                commandButton(player, "command.deathcensus.ui.input.discord_optout",
                        command + "discord " + (view.discordOptOut() ? "off" : "on") + " " + token, null),
                backButton(player)));
        line(player, ServerLang.tr(player, "command.deathcensus.ui.chat.privacy_hint"));
    }

    private static Component privacyStateLine(ServerPlayer player, String key, boolean value) {
        return ServerLang.tr(player, key, ServerLang.tr(player, value
                ? "command.deathcensus.ui.privacy.hidden"
                : "command.deathcensus.ui.privacy.visible"));
    }

    private static void renderNotice(ServerPlayer player, NoticeView view) {
        String raw = view.backAction();
        String bare = raw.startsWith("ui/") ? raw.substring(3) : raw;
        String back = UiActions.resolveAction(bare);
        if (back.isEmpty()) {
            back = "home";
        }
        line(player, ServerLang.tr(player, "command.deathcensus.ui.notice.title"));
        line(player, tr(player, view.message()));
        // NoticeView carries no target; a detail back-link cannot be re-resolved
        // from chat state, so Home is the safe destination here.
        if (!UiActions.isKnownAction(back) || "detail".equals(back)) {
            back = "home";
        }
        buttonRows(player, List.of(commandButton(player, "command.deathcensus.ui.ok",
                "/dc ui " + back, null)));
    }

    private static void renderConfirmation(ServerPlayer player, ConfirmationView view) {
        line(player, ServerLang.tr(player, view.title().key().isEmpty()
                ? "command.deathcensus.ui.confirm.title" : view.title().key()));
        line(player, tr(player, view.body()));
        if ("excuse_save".equals(view.yes().action())) {
            Map<String, String> params = view.yes().params();
            String target = params.getOrDefault("t", "");
            String epoch = params.getOrDefault("d", "0");
            line(player, ServerLang.tr(player, "command.deathcensus.ui.chat.excuse_hint", target, epoch));
            buttonRows(player, List.of(
                    suggestButton(player, "command.deathcensus.ui.excuse.save",
                            "/dc excuse " + target + " " + epoch + " ",
                            "command.deathcensus.ui.tooltip.save"),
                    commandButton(player, "command.deathcensus.ui.cancel", refCommand(view.no()), null)));
            return;
        }
        buttonRows(player, List.of(
                commandButton(player, "command.deathcensus.ui.yes", refCommand(view.yes()),
                        "command.deathcensus.ui.tooltip.confirm"),
                commandButton(player, "command.deathcensus.ui.no", refCommand(view.no()), null)));
    }

    /** Read-only shortcuts to existing operator commands (§8 default (b)). */
    private static void renderAdmin(ServerPlayer player, HomeView view) {
        line(player, ServerLang.tr(player, "command.deathcensus.ui.admin.title"));
        line(player, ServerLang.tr(player, "command.deathcensus.ui.admin.intro", view.totalDeaths()));
        buttonRows(player, List.of(
                suggestButton(player, "command.deathcensus.ui.admin.lookup", "/dc lookup ",
                        "command.deathcensus.ui.tooltip.lookup"),
                suggestButton(player, "command.deathcensus.ui.admin.history", "/dc history ",
                        "command.deathcensus.ui.tooltip.history"),
                commandButton(player, "command.deathcensus.ui.admin.backup", "/dc backup",
                        "command.deathcensus.ui.tooltip.backup"),
                suggestButton(player, "command.deathcensus.ui.admin.restore", "/dc restore ",
                        "command.deathcensus.ui.tooltip.restore"),
                commandButton(player, "command.deathcensus.ui.admin.doctor", "/dc doctor",
                        "command.deathcensus.ui.tooltip.doctor"),
                backButton(player)));
    }

    // --------------------------------------------------------------- helpers

    private static void line(ServerPlayer player, Component component) {
        player.sendSystemMessage(component);
    }

    private static Component tr(ServerPlayer player, UiText text) {
        return ServerLang.tr(player, text.key(), text.args());
    }

    private static Component button(ServerPlayer player, String labelKey, String command,
                                    String tooltipKey, boolean suggest) {
        ClickEvent.Action click = suggest ? ClickEvent.Action.SUGGEST_COMMAND : ClickEvent.Action.RUN_COMMAND;
        MutableComponent label = Component.literal("[" + ServerLang.tr(player, labelKey).getString() + "]");
        Style style = Style.EMPTY.withClickEvent(new ClickEvent(click, command));
        if (tooltipKey != null) {
            style = style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    ServerLang.tr(player, tooltipKey)));
        }
        return label.withStyle(style);
    }

    private static Component commandButton(ServerPlayer player, String labelKey, String command,
                                           String tooltipKey) {
        return button(player, labelKey, command, tooltipKey, false);
    }

    private static Component suggestButton(ServerPlayer player, String labelKey, String command,
                                           String tooltipKey) {
        return button(player, labelKey, command, tooltipKey, true);
    }

    private static Component backButton(ServerPlayer player) {
        return commandButton(player, "command.deathcensus.ui.back", "/dc ui home",
                "command.deathcensus.ui.tooltip.home");
    }

    private static void buttonRows(ServerPlayer player, List<Component> buttons) {
        for (int i = 0; i < buttons.size(); i += 2) {
            MutableComponent row = Component.literal("");
            row.append(buttons.get(i));
            if (i + 1 < buttons.size()) {
                row.append(" ").append(buttons.get(i + 1));
            }
            line(player, row);
        }
    }

    /** Builds the {@code /dc ui ...} command matching a common action reference. */
    private static String refCommand(UiActionRef ref) {
        Map<String, String> p = ref.params();
        String token = p.getOrDefault("s", "");
        String target = p.getOrDefault("t", "");
        String epoch = p.getOrDefault("d", "");
        return switch (ref.action()) {
            case "delete_confirm" -> "/dc ui delete " + target + " " + epoch + " confirm " + token;
            case "delete_ask" -> "/dc ui delete " + target + " " + epoch + " " + token;
            case "detail" -> "/dc ui detail " + target + " " + epoch + " " + token;
            case "excuse_save" -> "/dc excuse " + target + " " + epoch + " ";
            default -> "/dc ui " + ref.action() + (token.isEmpty() ? "" : " " + token);
        };
    }

    private static String truncate(String value) {
        return value.length() > UiActions.MAX_DISPLAY_LINE
                ? value.substring(0, UiActions.MAX_DISPLAY_LINE) : value;
    }
}
