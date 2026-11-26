package com.pengin0503.deathcensus.ui;

import com.pengin0503.deathcensus.CensusStats;
import com.pengin0503.deathcensus.CauseClassifier;
import com.pengin0503.deathcensus.DeathEntry;
import com.pengin0503.deathcensus.Pagination;
import com.pengin0503.deathcensus.PlayerDeaths;
import com.pengin0503.deathcensus.TimeFormat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Single dispatcher for both adapters. Every action id, payload field, page,
 * target and permission is validated here before any read or side effect.
 */
public final class UiActions {
    public static final Set<String> ACTIONS = Set.of(
            "home", "history", "history_search", "history_page", "detail",
            "excuse_open", "excuse_save", "respect", "rankings", "ranking_page",
            "privacy", "privacy_save", "delete_ask", "delete_confirm", "admin"
    );
    public static final int PAGE_SIZE = 10;
    public static final int MAX_PAGE = 10_000;
    public static final int MAX_PAYLOAD_BYTES = 1024;
    public static final int MAX_DISPLAY_LINE = 80;

    private static final String PREFIX = UiActionRef.NAMESPACE + ":ui/";
    private static final String ALL = HistoryFilter.ALL;
    private static final Set<String> BOUNDED_KEYS = Set.of(
            "s", "p", "t", "d", "f",
            "in_period", "in_category", "in_dimension", "in_query", "in_excuse", "in_optout", "in_type"
    );

    private UiActions() {
    }

    // ------------------------------------------------------- id/payload validation

    public static boolean isKnownAction(String action) {
        return action != null && ACTIONS.contains(action.trim());
    }

    /** Strict custom-click id check: only deathcensus:ui/&lt;known action&gt;. */
    public static Optional<String> actionFromIdentifier(String identifier) {
        if (identifier == null) {
            return Optional.empty();
        }
        String raw = identifier.trim();
        if (!raw.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String action = raw.substring(PREFIX.length());
        return isKnownAction(action) ? Optional.of(action.trim()) : Optional.empty();
    }

    /** Chat adapters pass bare names; custom clicks pass full identifiers. */
    public static String resolveAction(String raw) {
        if (isKnownAction(raw)) {
            return raw.trim();
        }
        return actionFromIdentifier(raw).orElse("");
    }

    /** UTF-8 size of a serialized payload, bounded well below the 1 KiB target. */
    public static int payloadBytes(Map<String, String> payload) {
        if (payload == null) {
            return 0;
        }
        int bytes = 0;
        for (Map.Entry<String, String> entry : payload.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey();
            String value = entry.getValue() == null ? "" : entry.getValue();
            bytes += key.getBytes(StandardCharsets.UTF_8).length + value.getBytes(StandardCharsets.UTF_8).length + 2;
        }
        return bytes;
    }

    public static boolean payloadWithinLimit(Map<String, String> payload) {
        return payloadBytes(payload) < MAX_PAYLOAD_BYTES;
    }

    /** Generic bounds from DESIGN §3.2; no input may throw on a malicious payload. */
    public static Optional<String> validatePayload(Map<String, String> payload) {
        if (!payloadWithinLimit(payload)) {
            return Optional.of("payload_too_large");
        }
        if (payload == null) {
            return Optional.empty();
        }
        for (Map.Entry<String, String> entry : payload.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey();
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (key.length() > 32) {
                return Optional.of("invalid_key");
            }
            if (!BOUNDED_KEYS.contains(key)) {
                continue; // schema fields (v/a) and unknown extras are ignored
            }
            switch (key) {
                case "in_period" -> {
                    if (!HistoryFilter.PERIODS.contains(
                            value.trim().toLowerCase(java.util.Locale.ROOT))) {
                        return Optional.of("invalid_period");
                    }
                }
                case "in_category" -> {
                    if (!ALL.equals(value.trim()) && !CauseClassifier.isKnownCategory(value.trim())
                            && value.trim().length() > 128) {
                        return Optional.of("invalid_category");
                    }
                }
                case "in_dimension" -> {
                    if (value.length() > 128) {
                        return Optional.of("invalid_dimension");
                    }
                }
                case "in_type" -> {
                    if (!UiSession.RankingSelection.TYPES.contains(value.trim().toLowerCase(java.util.Locale.ROOT))) {
                        return Optional.of("invalid_ranking_type");
                    }
                }
                case "in_query" -> {
                    if (value.length() > HistoryFilter.MAX_QUERY_LENGTH) {
                        return Optional.of("invalid_query");
                    }
                }
                case "in_excuse" -> {
                    if (value.length() > 100) {
                        return Optional.of("invalid_excuse");
                    }
                }
                case "in_optout" -> {
                    if (parseBoolean(value).isEmpty()) {
                        return Optional.of("invalid_optout");
                    }
                }
                case "p" -> {
                    if (parsePage(value).isEmpty()) {
                        return Optional.of("invalid_page");
                    }
                }
                case "t" -> {
                    if (parseUuid(value).isEmpty()) {
                        return Optional.of("invalid_uuid");
                    }
                }
                case "d" -> {
                    if (parseEpoch(value).isEmpty()) {
                        return Optional.of("invalid_epoch");
                    }
                }
                case "s" -> {
                    if (value.length() > 64) {
                        return Optional.of("invalid_session");
                    }
                }
                default -> {
                    // f (serialized filter) is consumed from the session only
                }
            }
        }
        return Optional.empty();
    }

    /** Adds context-dependent category/dimension checks to the generic bounds. */
    public static Optional<String> validateInputs(Map<String, String> inputs, UiContext ctx) {
        Optional<String> error = validatePayload(inputs);
        if (error.isPresent() || inputs == null || ctx == null) {
            return error;
        }
        Set<String> dimensions = knownDimensions(ctx);
        String category = inputs.get("in_category");
        if (category != null && !ALL.equals(category.trim()) && !CauseClassifier.isKnownCategory(category.trim())
                && !dimensions.contains(category.trim())) {
            return Optional.of("invalid_category");
        }
        String dimension = inputs.get("in_dimension");
        if (dimension != null && !ALL.equals(dimension.trim()) && !dimensions.contains(dimension.trim())) {
            return Optional.of("invalid_dimension");
        }
        return Optional.empty();
    }

    public static Optional<String> validatePage(int page) {
        return page >= 1 && page <= MAX_PAGE ? Optional.empty() : Optional.of("invalid_page");
    }

    public static Set<String> knownDimensions(UiContext ctx) {
        Set<String> dimensions = new HashSet<>();
        if (ctx == null) {
            return dimensions;
        }
        for (PlayerDeaths player : ctx.snapshot()) {
            if (player == null) {
                continue;
            }
            for (DeathEntry death : player.deaths()) {
                if (death != null && !death.dimension().isEmpty()) {
                    dimensions.add(death.dimension());
                }
            }
        }
        return dimensions;
    }

    public static Optional<UUID> parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value.trim()));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public static Optional<Long> parseEpoch(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            long epoch = Long.parseLong(value.trim());
            return epoch >= 0L ? Optional.of(epoch) : Optional.empty();
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    public static Optional<Integer> parsePage(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            int page = Integer.parseInt(value.trim());
            return page >= 1 && page <= MAX_PAGE ? Optional.of(page) : Optional.empty();
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    public static Optional<Boolean> parseBoolean(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "1" -> Optional.of(true);
            case "false", "0" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    // --------------------------------------------------------------- dispatch

    public static UiResult handle(UiRequest request, UiContext ctx) {
        if (request == null || ctx == null || request.viewer() == null) {
            return noChange("invalid_request");
        }
        String action = resolveAction(request.action());
        if (action.isEmpty()) {
            return noChange("unknown_action");
        }
        if (!payloadWithinLimit(request.inputs())) {
            return noChange("payload_too_large");
        }
        Optional<String> inputError = validateInputs(request.inputs(), ctx);
        if (inputError.isPresent()) {
            return noChange(inputError.get());
        }
        if (validatePage(request.page()).isPresent()) {
            return noChange("invalid_page");
        }
        return switch (action) {
            case "home" -> openHome(ctx);
            case "history", "history_search", "history_page" -> openHistory(request, ctx, action);
            case "detail" -> openDetail(request, ctx);
            case "excuse_open" -> openExcuse(request, ctx);
            case "excuse_save" -> saveExcuse(request, ctx);
            case "respect" -> respect(request, ctx);
            case "rankings", "ranking_page" -> openRankings(request, ctx, action);
            case "privacy" -> openPrivacy(request, ctx);
            case "privacy_save" -> savePrivacy(request, ctx);
            case "delete_ask" -> askDelete(request, ctx);
            case "delete_confirm" -> confirmDelete(request, ctx);
            case "admin" -> openAdmin(request, ctx);
            default -> noChange("unknown_action");
        };
    }

    // ------------------------------------------------------------------ screens

    private static UiResult openHome(UiContext ctx) {
        ctx.newSession(HistoryFilter.DEFAULT, UiSession.DEFAULT_RANKING);
        return new UiResult.Open(homeView(ctx));
    }

    private static HomeView homeView(UiContext ctx) {
        UUID viewer = ctx.viewer();
        List<PlayerDeaths> own = new ArrayList<>();
        for (PlayerDeaths player : ctx.snapshot()) {
            if (player != null && player.uuid().equalsIgnoreCase(viewer.toString())) {
                own.add(player);
            }
        }
        String lastLine = UiPrivacy.lastVisibleDeath(ctx.snapshot(), viewer, ctx.isAdmin())
                .map(death -> truncate(formatDeath(death, ctx.zoneId())))
                .orElse(UiText.RECORD_UNAVAILABLE_KEY);
        return new HomeView(
                CensusStats.totalDeaths(own),
                ctx.currentSurvivalTicks(viewer),
                UiPrivacy.anonymousServerTotal(ctx.snapshot()),
                lastLine,
                ctx.isAdmin()
        );
    }

    private static UiResult openHistory(UiRequest request, UiContext ctx, String action) {
        UiSession current = ctx.currentSession();
        HistoryFilter filter = "history_search".equals(action)
                ? new HistoryFilter(input(request, "in_period"), input(request, "in_category"),
                        input(request, "in_dimension"), input(request, "in_query"))
                : current == null ? HistoryFilter.DEFAULT : current.filter();
        UiSession.RankingSelection ranking = current == null ? UiSession.DEFAULT_RANKING : current.ranking();
        UiSession session = rotate(request, ctx, filter, ranking);
        if (session == null) {
            return expired(ctx);
        }
        return new UiResult.Open(historyView(ctx, filter, request.page(), session));
    }

    private static HistoryView historyView(UiContext ctx, HistoryFilter filter, int requestedPage, UiSession session) {
        List<DeathEntry> matching = UiPrivacy.visibleDeaths(ctx.snapshot(), ctx.viewer(), ctx.isAdmin()).stream()
                .filter(death -> filter.matches(death, ctx.nowMillis(), ctx.zoneId()))
                .sorted(Comparator.comparingLong(DeathEntry::epochMillis).reversed()
                        .thenComparing(DeathEntry::playerUuid))
                .toList();
        Pagination.Page<DeathEntry> page = Pagination.of(matching, requestedPage, PAGE_SIZE);
        int base = (page.page() - 1) * PAGE_SIZE;
        List<HistoryRow> rows = new ArrayList<>();
        for (int i = 0; i < page.items().size(); i++) {
            DeathEntry death = page.items().get(i);
            int number = page.total() - (base + i);
            rows.add(new HistoryRow(
                    death.epochMillis(),
                    death.playerUuid(),
                    UiPrivacy.displayName(ctx.snapshot(), death.playerUuid()),
                    truncate("#" + number + " · " + formatDeath(death, ctx.zoneId()))
            ));
        }
        return new HistoryView(rows, page.page(), page.pageCount(), page.total(), filter, session.token());
    }

    private static UiResult openDetail(UiRequest request, UiContext ctx) {
        DeathEntry death = resolveDeath(request, ctx);
        if (death == null || !ctx.canViewDetail(parseUuidOrNull(death.playerUuid()))) {
            return unavailable();
        }
        if (rotate(request, ctx, null, null) == null) {
            return expired(ctx);
        }
        return new UiResult.Open(detailView(ctx, death));
    }

    private static DeathDetailView detailView(UiContext ctx, DeathEntry death) {
        UUID target = parseUuidOrNull(death.playerUuid());
        return new DeathDetailView(
                death.playerUuid(),
                death.epochMillis(),
                UiPrivacy.displayName(ctx.snapshot(), death.playerUuid()),
                TimeFormat.formatDateTime(death.epochMillis(), ctx.zoneId()),
                death.dimension(),
                (int) Math.floor(death.x()),
                (int) Math.floor(death.y()),
                (int) Math.floor(death.z()),
                death.causeCategory(),
                death.deathMessage(),
                death.lastWords(),
                death.excuse(),
                death.fCount(),
                death.survivalTicks(),
                death.experienceLevel(),
                death.killerName(),
                UiPrivacy.canEditExcuse(ctx.viewer(), death.playerUuid()),
                UiPrivacy.canDelete(ctx.viewer(), death.playerUuid(), ctx.isAdmin())
        );
    }

    private static UiResult openRankings(UiRequest request, UiContext ctx, String action) {
        UiSession current = ctx.currentSession();
        UiSession.RankingSelection selection;
        if ("rankings".equals(action) && (input(request, "in_type") != null || input(request, "in_period") != null)) {
            selection = new UiSession.RankingSelection(
                    input(request, "in_type") != null
                            ? input(request, "in_type")
                            : current == null ? "deaths" : current.ranking().type(),
                    input(request, "in_period") != null
                            ? input(request, "in_period")
                            : current == null ? "all" : current.ranking().period()
            );
        } else {
            selection = current == null ? UiSession.DEFAULT_RANKING : current.ranking();
        }
        UiSession session = rotate(request, ctx,
                current == null ? HistoryFilter.DEFAULT : current.filter(), selection);
        if (session == null) {
            return expired(ctx);
        }
        return new UiResult.Open(rankingView(ctx, selection, request.page()));
    }

    private static RankingView rankingView(UiContext ctx, UiSession.RankingSelection selection, int requestedPage) {
        long since = HistoryFilter.DEFAULT.withPeriod(selection.period())
                .sinceMillis(ctx.nowMillis(), ctx.zoneId());
        List<PlayerDeaths> filtered = CensusStats.filterSince(ctx.snapshot(), since);
        List<String> entries = switch (selection.type()) {
            case "survival" -> {
                List<String> rows = new ArrayList<>();
                for (DeathEntry death : CensusStats.leaderboardSurvival(filtered, false)) {
                    rows.add(nameFor(ctx, death.playerUuid()) + " · "
                            + TimeFormat.formatDuration(death.survivalTicks()));
                }
                yield rows;
            }
            case "fastest" -> {
                List<String> rows = new ArrayList<>();
                for (DeathEntry death : CensusStats.leaderboardFastest(filtered, false)) {
                    rows.add(nameFor(ctx, death.playerUuid()) + " · "
                            + TimeFormat.formatDuration(death.survivalTicks()));
                }
                yield rows;
            }
            case "rarest" -> CensusStats.rarestCause(filtered, false)
                    .map(cause -> List.of(cause + " · 1"))
                    .orElseGet(List::of);
            default -> {
                List<String> rows = new ArrayList<>();
                for (CensusStats.PlayerSummary summary : CensusStats.leaderboardDeaths(filtered, false)) {
                    rows.add(summary.name() + " · " + summary.deaths());
                }
                yield rows;
            }
        };
        List<String> numbered = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            numbered.add("#" + (i + 1) + " · " + truncate(entries.get(i)));
        }
        Pagination.Page<String> page = Pagination.of(numbered, requestedPage, PAGE_SIZE);
        return new RankingView(
                selection.type(), selection.period(), page.page(), page.pageCount(),
                page.items(), truncate(UiPrivacy.anonymousTotalsLine(filtered))
        );
    }

    private static UiResult openPrivacy(UiRequest request, UiContext ctx) {
        if (rotate(request, ctx, null, null) == null) {
            return expired(ctx);
        }
        return new UiResult.Open(new PrivacyView(ctx.isOptedOut(ctx.viewer()),
                UiText.of("command.deathcensus.optout.done")));
    }

    private static UiResult savePrivacy(UiRequest request, UiContext ctx) {
        Optional<Boolean> optOut = parseBoolean(input(request, "in_optout"));
        if (optOut.isEmpty()) {
            return noChange("invalid_optout");
        }
        if (consume(request, ctx) == null) {
            return expired(ctx);
        }
        ctx.setOptOut(optOut.get());
        String key = optOut.get() ? "command.deathcensus.optout.done" : "command.deathcensus.optin.done";
        return new UiResult.SideEffect("privacy_save", true, UiText.of(key), new UiResult.Open(homeView(ctx)));
    }

    private static UiResult openExcuse(UiRequest request, UiContext ctx) {
        DeathEntry death = resolveDeath(request, ctx);
        if (death == null || !UiPrivacy.canEditExcuse(ctx.viewer(), death.playerUuid())) {
            return unavailable();
        }
        UiSession session = rotate(request, ctx, null, null);
        if (session == null) {
            return expired(ctx);
        }
        Map<String, String> params = targetParams(death, session);
        return new UiResult.Open(new ConfirmationView(
                UiText.of("command.deathcensus.ui.excuse.title"),
                UiText.of("command.deathcensus.ui.excuse.body"),
                new UiActionRef("excuse_save", params),
                new UiActionRef("detail", targetParams(death, session))
        ));
    }

    private static UiResult saveExcuse(UiRequest request, UiContext ctx) {
        DeathEntry death = resolveDeath(request, ctx);
        if (death == null || !UiPrivacy.canEditExcuse(ctx.viewer(), death.playerUuid())) {
            return unavailable();
        }
        String text = input(request, "in_excuse");
        if (text == null || text.isEmpty() || text.length() > 100) {
            return noChange("invalid_excuse");
        }
        if (consume(request, ctx) == null) {
            return expired(ctx);
        }
        boolean applied = ctx.setExcuse(parseUuidOrNull(death.playerUuid()), death.epochMillis(), text);
        return new UiResult.SideEffect("excuse_save", applied,
                UiText.of(applied ? "command.deathcensus.excuse.done" : UiText.RECORD_UNAVAILABLE_KEY),
                new UiResult.Open(detailView(ctx, death)));
    }

    private static UiResult respect(UiRequest request, UiContext ctx) {
        DeathEntry death = resolveDeath(request, ctx);
        if (death == null || !ctx.canViewDetail(parseUuidOrNull(death.playerUuid()))) {
            return unavailable();
        }
        if (consume(request, ctx) == null) {
            return expired(ctx);
        }
        boolean applied = ctx.payRespect(ctx.viewer(), parseUuidOrNull(death.playerUuid()), death.epochMillis());
        return new UiResult.SideEffect("respect", applied,
                UiText.of("command.deathcensus.ui.respect.done"),
                new UiResult.Open(detailView(ctx, death)));
    }

    private static UiResult askDelete(UiRequest request, UiContext ctx) {
        if (!ctx.isAdmin()) {
            return noChange("not_admin");
        }
        DeathEntry death = resolveDeath(request, ctx);
        if (death == null) {
            return unavailable();
        }
        UiSession session = rotate(request, ctx, null, null);
        if (session == null) {
            return expired(ctx);
        }
        Map<String, String> params = targetParams(death, session);
        return new UiResult.Open(new ConfirmationView(
                UiText.of("command.deathcensus.ui.delete.title"),
                UiText.of("command.deathcensus.ui.delete.body"),
                new UiActionRef("delete_confirm", params),
                new UiActionRef("detail", targetParams(death, session))
        ));
    }

    private static UiResult confirmDelete(UiRequest request, UiContext ctx) {
        if (!ctx.isAdmin()) {
            return noChange("not_admin");
        }
        DeathEntry death = resolveDeath(request, ctx);
        if (death == null) {
            return unavailable();
        }
        if (consume(request, ctx) == null) {
            return expired(ctx);
        }
        if (!ctx.deleteAfterBackup(parseUuidOrNull(death.playerUuid()), death.epochMillis())) {
            return new UiResult.Open(new NoticeView(
                    UiText.of("command.deathcensus.delete.backup_failed"), "ui/detail"));
        }
        UiSession session = ctx.newSession(HistoryFilter.DEFAULT, UiSession.DEFAULT_RANKING);
        return new UiResult.SideEffect("delete_confirm", true,
                UiText.of("command.deathcensus.delete.done"),
                new UiResult.Open(historyView(ctx, HistoryFilter.DEFAULT, 1, session)));
    }

    private static UiResult openAdmin(UiRequest request, UiContext ctx) {
        if (!ctx.isAdmin()) {
            return noChange("not_admin");
        }
        if (rotate(request, ctx, null, null) == null) {
            return expired(ctx);
        }
        // The adapter renders the read-only shortcut menu from HomeView.admin().
        return new UiResult.Open(homeView(ctx));
    }

    // ----------------------------------------------------------------- helpers

    private static UiSession rotate(
            UiRequest request, UiContext ctx, HistoryFilter filter, UiSession.RankingSelection ranking
    ) {
        UiSession current = ctx.currentSession();
        UiSession presented = null;
        if (!request.token().isBlank()) {
            if (current == null || !current.token().equals(request.token())) {
                return null;
            }
            presented = current;
        }
        HistoryFilter effectiveFilter = filter == null
                ? current == null ? HistoryFilter.DEFAULT : current.filter() : filter;
        UiSession.RankingSelection effectiveRanking = ranking == null
                ? current == null ? UiSession.DEFAULT_RANKING : current.ranking() : ranking;
        return ctx.touchSession(presented, effectiveFilter, effectiveRanking);
    }

    private static UiSession consume(UiRequest request, UiContext ctx) {
        UiSession current = ctx.currentSession();
        if (current == null || request.token().isBlank() || !current.token().equals(request.token())) {
            return null;
        }
        ctx.invalidateSession();
        return current;
    }

    private static UiResult expired(UiContext ctx) {
        ctx.sendNotice(UiText.of(UiText.SESSION_EXPIRED_KEY));
        return openHome(ctx);
    }

    private static UiResult unavailable() {
        return new UiResult.Open(NoticeView.of(UiText.RECORD_UNAVAILABLE_KEY));
    }

    private static UiResult noChange(String reason) {
        return new UiResult.NoChange(reason);
    }

    private static DeathEntry resolveDeath(UiRequest request, UiContext ctx) {
        UUID uuid = parseUuid(request.targetUuid()).orElse(null);
        if (uuid == null || request.deathEpoch() < 0L || ctx == null) {
            return null;
        }
        for (PlayerDeaths player : ctx.snapshot()) {
            if (player == null || !player.uuid().equalsIgnoreCase(uuid.toString())) {
                continue;
            }
            for (DeathEntry death : player.deaths()) {
                if (death.epochMillis() == request.deathEpoch()) {
                    return death;
                }
            }
        }
        return null;
    }

    private static UUID parseUuidOrNull(String raw) {
        return parseUuid(raw).orElse(null);
    }

    private static String input(UiRequest request, String key) {
        return request.inputs().get(key);
    }

    private static Map<String, String> targetParams(DeathEntry death, UiSession session) {
        Map<String, String> params = new HashMap<>();
        params.put("s", session.token());
        params.put("t", death.playerUuid());
        params.put("d", Long.toString(death.epochMillis()));
        params.put("p", "1");
        return params;
    }

    private static String nameFor(UiContext ctx, String uuid) {
        for (PlayerDeaths player : ctx.snapshot()) {
            if (player != null && player.uuid().equalsIgnoreCase(uuid)) {
                return player.name();
            }
        }
        return uuid;
    }

    private static String formatDeath(DeathEntry death, java.time.ZoneId zone) {
        String message = death.deathMessage().isEmpty() ? death.causeCategory() : death.deathMessage();
        StringBuilder line = new StringBuilder()
                .append(TimeFormat.formatDateTime(death.epochMillis(), zone))
                .append(" · ").append(death.dimension())
                .append(" (").append((int) Math.floor(death.x())).append(", ")
                .append((int) Math.floor(death.y())).append(", ")
                .append((int) Math.floor(death.z())).append(")")
                .append(" · ").append(message);
        if (!death.excuse().isEmpty()) {
            line.append(" · \"").append(death.excuse()).append("\"");
        }
        if (!death.lastWords().isEmpty()) {
            line.append(" · last words: \"").append(death.lastWords()).append("\"");
        }
        if (death.fCount() > 0) {
            line.append(" · F x").append(death.fCount());
        }
        return line.toString();
    }

    private static String truncate(String value) {
        return value.length() > MAX_DISPLAY_LINE ? value.substring(0, MAX_DISPLAY_LINE) : value;
    }
}
