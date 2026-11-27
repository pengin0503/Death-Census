package com.pengin0503.deathcensus.ui;

import com.pengin0503.deathcensus.CauseClassifier;
import com.pengin0503.deathcensus.Config;
import com.pengin0503.deathcensus.DeathContext;
import com.pengin0503.deathcensus.DeathCensus;
import com.pengin0503.deathcensus.ServerLang;
import com.pengin0503.deathcensus.TimeFormat;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.ConfirmationDialog;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.action.Action;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.ItemBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.BooleanInput;
import net.minecraft.server.dialog.input.SingleOptionInput;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The 26.x presentation adapter. Common view models become vanilla
 * server-driven dialogs; nothing here is cached across players, so every
 * component is localized through {@link ServerLang#tr(ServerPlayer, String, Object...)}
 * at render time.
 */
public final class DialogUi {
    private static final int LINE_WIDTH = 400;
    private static final int BUTTON_WIDTH = 200;
    private static final int INPUT_WIDTH = 200;
    private static final int COLUMNS = 2;

    private DialogUi() {
    }

    /** Entry point used by {@code /dc} and {@code /deathcensus} with no arguments. */
    public static void openHome(ServerPlayer player) {
        DialogUiContext ctx = new DialogUiContext(player);
        UiResult result = UiActions.handle(new UiRequest(player.getUUID(), "home"), ctx);
        render(player, "home", result);
    }

    /** Renders the result of a custom-click dispatch. */
    public static void openResult(ServerPlayer player, String action, UiResult result) {
        render(player, action, result);
    }

    /**
     * PR-4: one compact extra system line per online viewer after a death.
     * The gating predicate is the same UiPrivacy rule that history/detail use;
     * the line carries only the victim's name and (uuid, epoch) targets — never
     * coordinates or a death message — and the vanilla death message is untouched.
     */
    public static void sendDeathLine(ServerPlayer viewer, UUID victim, long epoch, String victimName) {
        DialogUiContext ctx = new DialogUiContext(viewer);
        if (!ctx.canViewDetail(victim)) {
            return;
        }
        MutableComponent line = ServerLang.tr(viewer, "command.deathcensus.ui.deathline", victimName);
        line.append(" ").append(deathButton(viewer, "command.deathcensus.ui.deathline.details",
                "detail", victim, epoch, "command.deathcensus.ui.tooltip.detail"));
        line.append(" ").append(deathButton(viewer, "command.deathcensus.ui.deathline.excuse",
                "excuse_open", victim, epoch, "command.deathcensus.ui.tooltip.excuse"));
        line.append(" ").append(deathButton(viewer, "command.deathcensus.ui.deathline.f",
                "respect", victim, epoch, "command.deathcensus.ui.tooltip.respect"));
        viewer.sendSystemMessage(line);
    }

    /** Custom-click button targeting one identified death; no session token is needed. */
    private static Component deathButton(ServerPlayer viewer, String labelKey, String action,
                                         UUID victim, long epoch, String tooltipKey) {
        CompoundTag payload = new CompoundTag();
        payload.putInt("v", 1);
        payload.putString("a", action);
        payload.putString("t", victim.toString());
        payload.putLong("d", epoch);
        ClickEvent click = new ClickEvent.Custom(
                Identifier.fromNamespaceAndPath(UiActionRef.NAMESPACE, "ui/" + action),
                Optional.of(payload));
        Style style = Style.EMPTY.withClickEvent(click)
                .withHoverEvent(new HoverEvent.ShowText(ServerLang.tr(viewer, tooltipKey)));
        return Component.literal("[" + ServerLang.tr(viewer, labelKey).getString() + "]").withStyle(style);
    }

    private static void render(ServerPlayer player, String requestedAction, UiResult result) {
        if (result instanceof UiResult.SideEffect sideEffect) {
            // UiActions builds parameterized feedback (including delete.done
            // with name/index/date), so every side effect is rendered here.
            if (sideEffect.feedback() != null && !sideEffect.feedback().key().isEmpty()) {
                player.sendSystemMessage(tr(player, sideEffect.feedback()));
            }
            result = sideEffect.followUp();
        }
        if (!(result instanceof UiResult.Open open)) {
            return;
        }
        Object view = open.view();
        Dialog dialog;
        if ("admin".equals(requestedAction) && view instanceof HomeView home) {
            dialog = adminDialog(player, home);
        } else if (view instanceof HomeView home) {
            dialog = homeDialog(player, home);
        } else if (view instanceof HistoryView history) {
            dialog = historyDialog(player, history);
        } else if (view instanceof DeathDetailView detail) {
            dialog = detailDialog(player, detail);
        } else if (view instanceof RankingView ranking) {
            dialog = rankingDialog(player, ranking);
        } else if (view instanceof PrivacyView privacy) {
            dialog = privacyDialog(player, privacy);
        } else if (view instanceof NoticeView notice) {
            dialog = noticeDialog(player, notice);
        } else if (view instanceof ConfirmationView confirmation) {
            dialog = confirmationDialog(player, confirmation);
        } else {
            return;
        }
        player.openDialog(Holder.direct(dialog));
    }

    // --------------------------------------------------------------- screens

    private static Dialog homeDialog(ServerPlayer player, HomeView view) {
        List<DialogBody> body = new ArrayList<>();
        body.add(line(ServerLang.tr(player, "command.deathcensus.ui.home.total", view.totalDeaths())));
        body.add(line(ServerLang.tr(player, "command.deathcensus.ui.home.survival",
                TimeFormat.formatDuration(view.currentSurvivalTicks()))));
        body.add(line(ServerLang.tr(player, "command.deathcensus.ui.home.server_total", view.serverTotalDeaths())));
        Component last = view.lastDeathLine().equals(UiText.RECORD_UNAVAILABLE_KEY)
                ? ServerLang.tr(player, UiText.RECORD_UNAVAILABLE_KEY)
                : Component.literal(truncate(view.lastDeathLine()));
        body.add(line(ServerLang.tr(player, "command.deathcensus.ui.home.last_death", last)));

        List<ActionButton> actions = new ArrayList<>();
        actions.add(commandButton(player, "command.deathcensus.ui.home.personal_stats", "/dc stats",
                "command.deathcensus.ui.tooltip.personal_stats"));
        actions.add(commandButton(player, "command.deathcensus.ui.home.server_stats", "/dc stats server",
                "command.deathcensus.ui.tooltip.server_stats"));
        actions.add(actionButton(player, "command.deathcensus.ui.home.rankings", "rankings", Map.of(),
                "command.deathcensus.ui.tooltip.rankings"));
        actions.add(actionButton(player, "command.deathcensus.ui.home.history", "history", Map.of(),
                "command.deathcensus.ui.tooltip.history"));
        actions.add(actionButton(player, "command.deathcensus.ui.home.privacy", "privacy", Map.of(),
                "command.deathcensus.ui.tooltip.privacy"));
        if (view.admin()) {
            actions.add(actionButton(player, "command.deathcensus.ui.home.admin", "admin", Map.of(),
                    "command.deathcensus.ui.tooltip.admin"));
        }
        return new MultiActionDialog(common(player, "command.deathcensus.ui.home.title", body, List.of()),
                actions, Optional.of(backButton(player)), COLUMNS);
    }

    private static Dialog historyDialog(ServerPlayer player, HistoryView view) {
        List<DialogBody> body = new ArrayList<>();
        body.add(line(ServerLang.tr(player, "command.deathcensus.ui.history.header",
                view.total(), view.page(), view.pageCount())));
        HistoryFilter filter = view.filter();
        body.add(line(ServerLang.tr(player, "command.deathcensus.ui.history.filter",
                filter.period(), filter.category(), filter.dimension(), filter.query())));
        if (view.rows().isEmpty()) {
            body.add(line(ServerLang.tr(player, "command.deathcensus.ui.history.empty")));
        }
        for (HistoryRow row : view.rows()) {
            body.add(line(Component.literal(truncate(row.displayLine()))));
        }

        String token = view.sessionToken();
        List<ActionButton> actions = new ArrayList<>();
        actions.add(actionButton(player, "command.deathcensus.ui.history.search", "history_search",
                Map.of("s", token, "p", "1"), "command.deathcensus.ui.tooltip.search"));
        actions.add(actionButton(player, "command.deathcensus.ui.prev", "history_page",
                Map.of("s", token, "p", Integer.toString(Math.max(1, view.page() - 1))), null));
        actions.add(actionButton(player, "command.deathcensus.ui.next", "history_page",
                Map.of("s", token, "p", Integer.toString(Math.min(Math.max(1, view.pageCount()), view.page() + 1))),
                null));
        for (HistoryRow row : view.rows()) {
            actions.add(actionButton(player, "command.deathcensus.ui.history.open", "detail",
                    targetParams(row.targetUuid(), row.epochMillis(), token, view.page()),
                    "command.deathcensus.ui.tooltip.detail"));
        }
        List<Input> inputs = List.of(
                choiceInput(player, "in_period", "command.deathcensus.ui.input.period",
                        HistoryFilter.PERIODS, filter.period()),
                choiceInput(player, "in_category", "command.deathcensus.ui.input.category",
                        categories(player, filter.category()), filter.category()),
                choiceInput(player, "in_dimension", "command.deathcensus.ui.input.dimension",
                        dimensions(player, filter.dimension()), filter.dimension()),
                textInput(player, "in_query", "command.deathcensus.ui.input.query", filter.query(),
                        HistoryFilter.MAX_QUERY_LENGTH));
        return new MultiActionDialog(common(player, "command.deathcensus.ui.history.title", body, inputs),
                actions, Optional.of(backButton(player)), COLUMNS);
    }

    /** PR-3 context lines in the dialog detail view (DESIGN 1.4.0 §11). */
    private static void appendContextLines(List<DialogBody> body, ServerPlayer player,
                                           DeathContext context) {
        if (context == null || context.isEmpty()) {
            body.add(line(ServerLang.tr(player, "command.deathcensus.ui.detail.context.empty")));
            return;
        }
        if (!context.mainHand().isEmpty()) {
            body.add(line(ServerLang.tr(player, "command.deathcensus.ui.detail.context.main_hand",
                    context.mainHand())));
        }
        if (!context.armor().isEmpty()) {
            body.add(line(ServerLang.tr(player, "command.deathcensus.ui.detail.context.armor",
                    String.join(", ", context.armor()))));
        }
        if (!context.effects().isEmpty()) {
            body.add(line(ServerLang.tr(player, "command.deathcensus.ui.detail.context.effects",
                    String.join(", ", context.effects()))));
        }
        if (!context.biome().isEmpty()) {
            body.add(line(ServerLang.tr(player, "command.deathcensus.ui.detail.context.biome",
                    context.biome())));
        }
        if (!context.weather().isEmpty()) {
            body.add(line(ServerLang.tr(player, "command.deathcensus.ui.detail.context.weather",
                    context.weather())));
        }
    }

    private static Dialog detailDialog(ServerPlayer player, DeathDetailView view) {
        List<DialogBody> body = new ArrayList<>();
        body.add(line(ServerLang.tr(player, "command.deathcensus.ui.detail.date", view.dateTime())));
        body.add(view.coordsHidden()
                ? line(ServerLang.tr(player, "command.deathcensus.ui.detail.coords_hidden", view.dimension()))
                : line(ServerLang.tr(player, "command.deathcensus.ui.detail.coords",
                        view.x(), view.y(), view.z(), view.dimension())));
        body.add(line(ServerLang.tr(player, "command.deathcensus.ui.detail.cause",
                view.causeCategory(), truncate(view.deathMessage()))));
        body.add(line(ServerLang.tr(player, "command.deathcensus.ui.detail.killer",
                view.killerName().isEmpty() ? ServerLang.tr(player, "command.deathcensus.ui.none") : view.killerName())));
        body.add(line(ServerLang.tr(player, "command.deathcensus.ui.detail.survival",
                TimeFormat.formatDuration(safeTicks(view.survivalTicks())))));
        body.add(line(ServerLang.tr(player, "command.deathcensus.ui.detail.xp", view.experienceLevel())));
        body.add(line(ServerLang.tr(player, "command.deathcensus.ui.detail.last_words",
                view.lastWords().isEmpty() ? ServerLang.tr(player, "command.deathcensus.ui.none") : truncate(view.lastWords()))));
        body.add(line(ServerLang.tr(player, "command.deathcensus.ui.detail.excuse",
                view.excuse().isEmpty() ? ServerLang.tr(player, "command.deathcensus.ui.none") : truncate(view.excuse()))));
        body.add(line(ServerLang.tr(player, "command.deathcensus.ui.detail.f_count", view.fCount())));
        body.add(line(ServerLang.tr(player, "command.deathcensus.ui.detail.witnesses",
                view.witnesses().isEmpty()
                        ? ServerLang.tr(player, "command.deathcensus.ui.none")
                        : String.join(", ", view.witnesses()))));
        appendContextLines(body, player, view.context());
        body.add(new ItemBody(causeItem(view.causeCategory()),
                Optional.of(new PlainMessage(
                        ServerLang.tr(player, "command.deathcensus.ui.detail.item_tooltip", view.causeCategory()),
                        LINE_WIDTH)),
                false, true, 64, 64));

        String token = sessionToken(player);
        Map<String, String> target = targetParams(view.targetUuid(), view.epochMillis(), token, 1);
        List<ActionButton> actions = new ArrayList<>();
        if (view.canEditExcuse()) {
            actions.add(actionButton(player, "command.deathcensus.ui.detail.excuse_button",
                    "excuse_open", target, "command.deathcensus.ui.tooltip.excuse"));
        }
        actions.add(actionButton(player, "command.deathcensus.ui.detail.respect", "respect", target,
                "command.deathcensus.ui.tooltip.respect"));
        actions.add(actionButton(player, "command.deathcensus.ui.detail.history", "history", Map.of(), null));
        if (view.canDelete()) {
            actions.add(actionButton(player, "command.deathcensus.ui.detail.delete", "delete_ask", target,
                    "command.deathcensus.ui.tooltip.delete"));
        }
        return new MultiActionDialog(common(player, "command.deathcensus.ui.detail.title", body, List.of()),
                actions, Optional.of(backButton(player)), COLUMNS);
    }

    private static Dialog rankingDialog(ServerPlayer player, RankingView view) {
        List<DialogBody> body = new ArrayList<>();
        String language = player.clientInformation() != null
                ? player.clientInformation().language() : ServerLang.DEFAULT_LANG;
        String typeLabel = switch (view.type()) {
            case "awards" -> ServerLang.formatForLang(language, "command.deathcensus.ui.ranking.awards");
            case "chronicle" -> ServerLang.formatForLang(language, "command.deathcensus.ui.ranking.chronicle");
            case "nemesis" -> ServerLang.formatForLang(language, "command.deathcensus.ui.ranking.nemesis");
            case "hotspots" -> ServerLang.formatForLang(language, "command.deathcensus.ui.ranking.hotspots");
            default -> view.type();
        };
        body.add(line(ServerLang.tr(player, "command.deathcensus.ui.rankings.header",
                typeLabel, view.period(), view.page(), view.pageCount())));
        if (view.rows().isEmpty()) {
            body.add(line(ServerLang.tr(player, "command.deathcensus.ui.rankings.empty")));
        }
        for (String row : view.rows()) {
            body.add(line(Component.literal(truncate(row))));
        }
        if (!view.anonymousTotalsLine().isEmpty()) {
            body.add(line(ServerLang.tr(player, "command.deathcensus.ui.rankings.footer",
                    truncate(view.anonymousTotalsLine()))));
        }
        String token = sessionToken(player);
        List<ActionButton> actions = List.of(
                actionButton(player, "command.deathcensus.ui.rankings.apply", "rankings",
                        Map.of("s", token, "p", "1"), "command.deathcensus.ui.tooltip.apply"),
                actionButton(player, "command.deathcensus.ui.prev", "ranking_page",
                        Map.of("s", token, "p", Integer.toString(Math.max(1, view.page() - 1))), null),
                actionButton(player, "command.deathcensus.ui.next", "ranking_page",
                        Map.of("s", token, "p", Integer.toString(Math.min(Math.max(1, view.pageCount()),
                                view.page() + 1))), null));
        List<String> types = new ArrayList<>(List.of("deaths", "survival", "fastest", "rarest"));
        if (Config.awardsEnabled()) {
            types.add("awards");
        }
        if (Config.chronicleEnabled()) {
            types.add("chronicle");
        }
        if (Config.nemesisEnabled()) {
            types.add("nemesis");
        }
        if (Config.hotspotsEnabled()) {
            types.add("hotspots");
        }
        List<Input> inputs = List.of(
                choiceInput(player, "in_type", "command.deathcensus.ui.input.type",
                        types, view.type()),
                choiceInput(player, "in_period", "command.deathcensus.ui.input.period",
                        HistoryFilter.PERIODS, view.period()));
        return new MultiActionDialog(common(player, "command.deathcensus.ui.rankings.title", body, inputs),
                actions, Optional.of(backButton(player)), COLUMNS);
    }

    private static Dialog privacyDialog(ServerPlayer player, PrivacyView view) {
        List<DialogBody> body = new ArrayList<>();
        body.add(privacyLine(player, "command.deathcensus.ui.privacy.full", view.optOut()));
        body.add(privacyLine(player, "command.deathcensus.ui.privacy.coords", view.hideCoordinates()));
        body.add(privacyLine(player, "command.deathcensus.ui.privacy.opponent", view.hidePvpOpponent()));
        body.add(privacyLine(player, "command.deathcensus.ui.privacy.discord", view.discordOptOut()));
        body.add(line(tr(player, view.explanation())));
        List<Input> inputs = List.of(
                booleanInput(player, "in_optout", "command.deathcensus.ui.input.optout", view.optOut()),
                booleanInput(player, "in_hide_coords", "command.deathcensus.ui.input.hide_coords",
                        view.hideCoordinates()),
                booleanInput(player, "in_hide_opponent", "command.deathcensus.ui.input.hide_opponent",
                        view.hidePvpOpponent()),
                booleanInput(player, "in_discord_optout", "command.deathcensus.ui.input.discord_optout",
                        view.discordOptOut()));
        List<ActionButton> actions = List.of(actionButton(player, "command.deathcensus.ui.privacy.save",
                "privacy_save", Map.of("s", sessionToken(player), "p", "1"),
                "command.deathcensus.ui.tooltip.save"));
        return new MultiActionDialog(common(player, "command.deathcensus.ui.privacy.title", body, inputs),
                actions, Optional.of(backButton(player)), 1);
    }

    /** One localized state line per flag; both words come from bundled lang keys. */
    private static DialogBody privacyLine(ServerPlayer player, String key, boolean value) {
        return line(ServerLang.tr(player, key, ServerLang.tr(player, value
                ? "command.deathcensus.ui.privacy.hidden"
                : "command.deathcensus.ui.privacy.visible")));
    }

    private static Input booleanInput(ServerPlayer player, String id, String labelKey, boolean value) {
        return new Input(id, new BooleanInput(ServerLang.tr(player, labelKey), value, "true", "false"));
    }

    private static Dialog noticeDialog(ServerPlayer player, NoticeView view) {
        // NoticeView carries "ui/<action>" rather than a full identifier.
        String raw = view.backAction();
        String bare = raw.startsWith("ui/") ? raw.substring(3) : raw;
        String back = UiActions.resolveAction(bare);
        if (back.isEmpty()) {
            back = "home";
        }
        ActionButton ok = actionButton(player, "command.deathcensus.ui.ok", back,
                Map.of("s", sessionToken(player), "p", "1"), null);
        return new NoticeDialog(common(player, "command.deathcensus.ui.notice.title",
                List.of(line(tr(player, view.message()))), List.of(), DialogAction.CLOSE), ok);
    }

    /** Confirmation for delete; the excuse editor is a MultiActionDialog (it needs a text input). */
    private static Dialog confirmationDialog(ServerPlayer player, ConfirmationView view) {
        if ("excuse_save".equals(view.yes().action())) {
            return excuseDialog(player, view);
        }
        return new ConfirmationDialog(common(player, view.title().key().isEmpty()
                        ? "command.deathcensus.ui.confirm.title" : view.title().key(),
                List.of(line(tr(player, view.body()))), List.of()),
                refButton(player, "command.deathcensus.ui.yes", view.yes(),
                        "command.deathcensus.ui.tooltip.confirm"),
                refButton(player, "command.deathcensus.ui.no", view.no(), null));
    }

    private static Dialog excuseDialog(ServerPlayer player, ConfirmationView view) {
        List<DialogBody> body = new ArrayList<>();
        body.add(line(tr(player, view.title())));
        body.add(line(tr(player, view.body())));
        List<Input> inputs = List.of(textInput(player, "in_excuse", "command.deathcensus.ui.input.excuse",
                "", 100));
        List<ActionButton> actions = List.of(
                refButton(player, "command.deathcensus.ui.excuse.save", view.yes(),
                        "command.deathcensus.ui.tooltip.save"),
                refButton(player, "command.deathcensus.ui.cancel", view.no(), null));
        return new MultiActionDialog(common(player, "command.deathcensus.ui.excuse.title", body, inputs),
                actions, Optional.of(backButton(player)), 1);
    }

    /** Read-only shortcuts to the existing operator commands (§8 default (b)). */
    private static Dialog adminDialog(ServerPlayer player, HomeView view) {
        List<DialogBody> body = List.of(line(ServerLang.tr(player, "command.deathcensus.ui.admin.intro",
                view.totalDeaths())));
        List<ActionButton> actions = List.of(
                suggestButton(player, "command.deathcensus.ui.admin.lookup", "/dc lookup ",
                        "command.deathcensus.ui.tooltip.lookup"),
                suggestButton(player, "command.deathcensus.ui.admin.history", "/dc history ",
                        "command.deathcensus.ui.tooltip.history"),
                commandButton(player, "command.deathcensus.ui.admin.backup", "/dc backup",
                        "command.deathcensus.ui.tooltip.backup"),
                suggestButton(player, "command.deathcensus.ui.admin.restore", "/dc restore ",
                        "command.deathcensus.ui.tooltip.restore"),
                commandButton(player, "command.deathcensus.ui.admin.doctor", "/dc doctor",
                        "command.deathcensus.ui.tooltip.doctor"));
        return new MultiActionDialog(common(player, "command.deathcensus.ui.admin.title", body, List.of()),
                actions, Optional.of(backButton(player)), COLUMNS);
    }

    // --------------------------------------------------------------- helpers

    private static CommonDialogData common(ServerPlayer player, String titleKey,
                                           List<DialogBody> body, List<Input> inputs) {
        return common(player, titleKey, body, inputs, DialogAction.WAIT_FOR_RESPONSE);
    }

    private static CommonDialogData common(ServerPlayer player, String titleKey,
                                           List<DialogBody> body, List<Input> inputs, DialogAction afterAction) {
        // pause=false keeps singleplayer/LAN unpaused and satisfies the codec
        // (pause && !afterAction.willUnpause() is rejected).
        return new CommonDialogData(ServerLang.tr(player, titleKey), Optional.empty(),
                true, false, afterAction, body, inputs);
    }

    private static PlainMessage line(Component component) {
        return new PlainMessage(component, LINE_WIDTH);
    }

    private static Component tr(ServerPlayer player, UiText text) {
        return ServerLang.tr(player, text.key(), text.args());
    }

    private static ActionButton actionButton(ServerPlayer player, String labelKey, String action,
                                             Map<String, String> params, String tooltipKey) {
        Map<String, String> all = new LinkedHashMap<>(params);
        all.put("a", action);
        CompoundTag additions = new CompoundTag();
        additions.putInt("v", 1); // IntTag: DialogClickHandler requires a numeric schema version
        for (Map.Entry<String, String> entry : all.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            switch (key) {
                case "p" -> additions.putInt("p", parsePage(value));
                case "d" -> additions.putLong("d", parseLong(value));
                default -> additions.putString(key, value);
            }
        }
        Action click = new CustomAll(Identifier.fromNamespaceAndPath(DeathCensus.MOD_ID, "ui/" + action),
                Optional.of(additions));
        return new ActionButton(buttonData(player, labelKey, tooltipKey), Optional.of(click));
    }

    private static ActionButton refButton(ServerPlayer player, String labelKey, UiActionRef ref,
                                          String tooltipKey) {
        return actionButton(player, labelKey, ref.action(), ref.params(), tooltipKey);
    }

    private static ActionButton commandButton(ServerPlayer player, String labelKey, String command,
                                              String tooltipKey) {
        Action click = new StaticAction(new ClickEvent.RunCommand(command));
        return new ActionButton(buttonData(player, labelKey, tooltipKey), Optional.of(click));
    }

    /** Tab-completed argument shortcut (verified SUGGEST_COMMAND is server-allowed in 26.x). */
    private static ActionButton suggestButton(ServerPlayer player, String labelKey, String command,
                                              String tooltipKey) {
        Action click = new StaticAction(new ClickEvent.SuggestCommand(command));
        return new ActionButton(buttonData(player, labelKey, tooltipKey), Optional.of(click));
    }

    private static CommonButtonData buttonData(ServerPlayer player, String labelKey, String tooltipKey) {
        Optional<Component> tooltip = tooltipKey == null
                ? Optional.empty()
                : Optional.of(ServerLang.tr(player, tooltipKey));
        return new CommonButtonData(ServerLang.tr(player, labelKey), tooltip, BUTTON_WIDTH);
    }

    private static ActionButton backButton(ServerPlayer player) {
        return actionButton(player, "command.deathcensus.ui.back", "home", Map.of(),
                "command.deathcensus.ui.tooltip.home");
    }

    private static Map<String, String> targetParams(String uuid, long epoch, String token, int page) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("s", token);
        params.put("p", Integer.toString(page));
        params.put("t", uuid);
        params.put("d", Long.toString(epoch));
        return params;
    }

    private static String sessionToken(ServerPlayer player) {
        UiSession session = new DialogUiContext(player).currentSession();
        return session == null ? "" : session.token();
    }

    private static Input choiceInput(ServerPlayer player, String key, String labelKey,
                                     Collection<String> values, String selected) {
        List<SingleOptionInput.Entry> entries = new ArrayList<>();
        for (String value : values) {
            entries.add(new SingleOptionInput.Entry(value, Optional.of(Component.literal(value)),
                    value.equals(selected)));
        }
        return new Input(key, new SingleOptionInput(INPUT_WIDTH, entries,
                ServerLang.tr(player, labelKey), true));
    }

    private static Input textInput(ServerPlayer player, String key, String labelKey,
                                   String initial, int maxLength) {
        return new Input(key, new TextInput(INPUT_WIDTH, ServerLang.tr(player, labelKey),
                true, initial, maxLength, Optional.empty()));
    }

    private static List<String> categories(ServerPlayer player, String selected) {
        List<String> values = new ArrayList<>();
        values.add(HistoryFilter.ALL);
        values.addAll(CauseClassifier.CATEGORIES);
        if (!values.contains(selected)) {
            values.add(selected);
        }
        return values;
    }

    private static Set<String> dimensions(ServerPlayer player, String selected) {
        Set<String> values = new java.util.LinkedHashSet<>();
        values.add(HistoryFilter.ALL);
        values.addAll(UiActions.knownDimensions(new DialogUiContext(player)));
        if (!selected.isEmpty() && !selected.equals(HistoryFilter.ALL)) {
            values.add(selected);
        }
        return values;
    }

    private static ItemStackTemplate causeItem(String category) {
        Item item = switch (category) {
            case "fall" -> Items.FEATHER;
            case "drown" -> Items.WATER_BUCKET;
            case "fire" -> Items.FLINT_AND_STEEL;
            case "lava" -> Items.LAVA_BUCKET;
            case "freeze" -> Items.ICE;
            case "starve" -> Items.BREAD;
            case "suffocate" -> Items.SAND;
            case "cactus" -> Items.CACTUS;
            case "explosion" -> Items.TNT;
            case "magic", "lightning" -> Items.ENCHANTED_BOOK;
            case "projectile" -> Items.ARROW;
            case "player" -> Items.DIAMOND_SWORD;
            case "mob" -> Items.ZOMBIE_HEAD;
            case "void" -> Items.ENDER_PEARL;
            default -> Items.CLOCK;
        };
        Registry<Item> registry = BuiltInRegistries.ITEM;
        return new ItemStackTemplate(registry.wrapAsHolder(item));
    }

    private static int safeTicks(long ticks) {
        return (int) Math.max(0L, Math.min(ticks, Integer.MAX_VALUE));
    }

    private static int parsePage(String value) {
        try {
            return Math.max(1, Math.min(UiActions.MAX_PAGE, Integer.parseInt(value)));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static long parseLong(String value) {
        try {
            return Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String truncate(String value) {
        return value.length() > UiActions.MAX_DISPLAY_LINE
                ? value.substring(0, UiActions.MAX_DISPLAY_LINE)
                : value;
    }
}
