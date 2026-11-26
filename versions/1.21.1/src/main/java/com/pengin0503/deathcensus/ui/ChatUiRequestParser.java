package com.pengin0503.deathcensus.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure Java parser for the hidden {@code /dc ui ...} Brigadier subtree. Its
 * positional arguments encode the PR-1 {@link UiActionRef} scheme ({@code s}
 * session token, {@code p} page, {@code t} target uuid, {@code d} death epoch
 * and {@code in_*} inputs), so the very same {@link UiRequest} can be produced
 * from a chat command and from a 26.x custom click. No Minecraft classes are
 * used here; the Brigadier nodes delegate to this parser, which also makes the
 * protocol directly unit-testable.
 */
public final class ChatUiRequestParser {
    private static final int MAX_TOKEN_LENGTH = 64;

    private ChatUiRequestParser() {
    }

    public static Optional<UiRequest> parse(UUID viewer, String... args) {
        return parse(viewer, args == null ? List.of() : Arrays.asList(args));
    }

    public static Optional<UiRequest> parse(UUID viewer, List<String> rawArgs) {
        if (viewer == null || rawArgs == null || rawArgs.isEmpty()) {
            return Optional.empty();
        }
        List<String> args = normalize(rawArgs);
        if (args.isEmpty()) {
            return Optional.empty();
        }
        String action = args.get(0).toLowerCase(Locale.ROOT);
        return switch (action) {
            case "home" -> args.size() == 1
                    ? Optional.of(request(viewer, "home", "", 1, "", -1L, Map.of()))
                    : Optional.empty();
            case "history" -> parseHistory(viewer, args);
            case "detail", "respect" -> parseTarget(viewer, action, args);
            case "delete" -> parseDelete(viewer, args);
            case "excuse" -> parseExcuse(viewer, args);
            case "rankings" -> parseRankings(viewer, args);
            case "privacy" -> parsePrivacy(viewer, args);
            case "admin" -> args.size() == 1
                    ? Optional.of(request(viewer, "admin", "", 1, "", -1L, Map.of()))
                    : Optional.empty();
            default -> Optional.empty();
        };
    }

    /** Tolerates a full command line ({@code /dc ui ...}) as well as bare {@code ui} arguments. */
    private static List<String> normalize(List<String> rawArgs) {
        List<String> args = new ArrayList<>();
        for (String raw : rawArgs) {
            if (raw == null) {
                return List.of();
            }
            String value = raw.trim();
            if (value.startsWith("/")) {
                value = value.substring(1);
            }
            if (!value.isEmpty()) {
                args.add(value);
            }
        }
        if (!args.isEmpty() && ("dc".equals(args.get(0)) || "deathcensus".equals(args.get(0)))) {
            args.remove(0);
        }
        if (!args.isEmpty() && "ui".equals(args.get(0))) {
            args.remove(0);
        }
        return args;
    }

    private static Optional<UiRequest> parseHistory(UUID viewer, List<String> args) {
        if (args.size() == 1) {
            return Optional.of(request(viewer, "history", "", 1, "", -1L, Map.of()));
        }
        if ("filter".equalsIgnoreCase(args.get(1))) {
            return parseHistoryFilter(viewer, args);
        }
        Optional<Integer> page = UiActions.parsePage(args.get(1));
        if (page.isEmpty()) {
            return Optional.empty();
        }
        String token = args.size() >= 3 ? args.get(2) : "";
        if (args.size() > 3 || !validToken(token)) {
            return Optional.empty();
        }
        return Optional.of(request(viewer, "history", token, page.get(), "", -1L, Map.of()));
    }

    /** {@code history filter <period> [category] [query words...]} (§5). */
    private static Optional<UiRequest> parseHistoryFilter(UUID viewer, List<String> args) {
        if (args.size() < 3) {
            return Optional.empty();
        }
        String period = args.get(2).toLowerCase(Locale.ROOT);
        if (!HistoryFilter.PERIODS.contains(period)) {
            return Optional.empty();
        }
        String category = args.size() >= 4 ? args.get(3) : HistoryFilter.ALL;
        String query = args.size() > 4 ? String.join(" ", args.subList(4, args.size())) : "";
        if (query.length() > HistoryFilter.MAX_QUERY_LENGTH || category.length() > 128) {
            return Optional.empty();
        }
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("in_period", period);
        // Dimension ids contain ':' while cause categories do not: the chat
        // command's single category slot can carry either (DESIGN §3.1).
        if (category.contains(":")) {
            inputs.put("in_category", HistoryFilter.ALL);
            inputs.put("in_dimension", category);
        } else {
            inputs.put("in_category", category);
            inputs.put("in_dimension", HistoryFilter.ALL);
        }
        inputs.put("in_query", query);
        return Optional.of(request(viewer, "history_search", "", 1, "", -1L, inputs));
    }

    private static Optional<UiRequest> parseTarget(UUID viewer, String action, List<String> args) {
        if (args.size() < 3 || args.size() > 4) {
            return Optional.empty();
        }
        String uuid = args.get(1);
        Optional<Long> epoch = UiActions.parseEpoch(args.get(2));
        String token = args.size() == 4 ? args.get(3) : "";
        if (UiActions.parseUuid(uuid).isEmpty() || epoch.isEmpty() || !validToken(token)) {
            return Optional.empty();
        }
        return Optional.of(request(viewer, action, token, 1, uuid, epoch.get(), Map.of()));
    }

    private static Optional<UiRequest> parseDelete(UUID viewer, List<String> args) {
        if (args.size() < 3 || args.size() > 5) {
            return Optional.empty();
        }
        String uuid = args.get(1);
        Optional<Long> epoch = UiActions.parseEpoch(args.get(2));
        if (UiActions.parseUuid(uuid).isEmpty() || epoch.isEmpty()) {
            return Optional.empty();
        }
        int index = 3;
        boolean confirm = false;
        if (index < args.size() && "confirm".equalsIgnoreCase(args.get(index))) {
            confirm = true;
            index++;
        }
        if (index > args.size()) {
            return Optional.empty();
        }
        String token = index < args.size() ? args.get(index) : "";
        if (index < args.size() && index + 1 != args.size()) {
            return Optional.empty();
        }
        if (!validToken(token)) {
            return Optional.empty();
        }
        return Optional.of(request(viewer, confirm ? "delete_confirm" : "delete_ask",
                token, 1, uuid, epoch.get(), Map.of()));
    }

    private static Optional<UiRequest> parseExcuse(UUID viewer, List<String> args) {
        if (args.size() < 3) {
            return Optional.empty();
        }
        String uuid = args.get(1);
        Optional<Long> epoch = UiActions.parseEpoch(args.get(2));
        if (UiActions.parseUuid(uuid).isEmpty() || epoch.isEmpty()) {
            return Optional.empty();
        }
        int index = 3;
        String token = "";
        if (index < args.size() && !"save".equalsIgnoreCase(args.get(index))
                && validToken(args.get(index))) {
            token = args.get(index++);
        }
        if (index < args.size() && "save".equalsIgnoreCase(args.get(index))) {
            String text = String.join(" ", args.subList(index + 1, args.size()));
            if (text.isEmpty() || text.length() > 100) {
                return Optional.empty();
            }
            Map<String, String> inputs = new LinkedHashMap<>();
            inputs.put("in_excuse", text);
            return Optional.of(request(viewer, "excuse_save", token, 1, uuid, epoch.get(), inputs));
        }
        if (index != args.size() || !validToken(token)) {
            return Optional.empty();
        }
        return Optional.of(request(viewer, "excuse_open", token, 1, uuid, epoch.get(), Map.of()));
    }

    private static Optional<UiRequest> parseRankings(UUID viewer, List<String> args) {
        if (args.size() == 1) {
            return Optional.of(request(viewer, "rankings", "", 1, "", -1L, Map.of()));
        }
        if ("page".equalsIgnoreCase(args.get(1))) {
            if (args.size() < 3 || args.size() > 4) {
                return Optional.empty();
            }
            Optional<Integer> page = UiActions.parsePage(args.get(2));
            String token = args.size() == 4 ? args.get(3) : "";
            if (page.isEmpty() || !validToken(token)) {
                return Optional.empty();
            }
            return Optional.of(request(viewer, "ranking_page", token, page.get(), "", -1L, Map.of()));
        }
        if (args.size() < 3 || args.size() > 5) {
            return Optional.empty();
        }
        String type = args.get(1).toLowerCase(Locale.ROOT);
        String period = args.get(2).toLowerCase(Locale.ROOT);
        if (!UiSession.RankingSelection.TYPES.contains(type) || !HistoryFilter.PERIODS.contains(period)) {
            return Optional.empty();
        }
        int page = 1;
        int index = 3;
        if (index < args.size() && UiActions.parsePage(args.get(index)).isPresent()) {
            page = UiActions.parsePage(args.get(index)).get();
            index++;
        }
        if (index > args.size()) {
            return Optional.empty();
        }
        String token = index < args.size() ? args.get(index) : "";
        if (index < args.size() && index + 1 != args.size()) {
            return Optional.empty();
        }
        if (!validToken(token)) {
            return Optional.empty();
        }
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("in_type", type);
        inputs.put("in_period", period);
        return Optional.of(request(viewer, "rankings", token, page, "", -1L, inputs));
    }

    private static Optional<UiRequest> parsePrivacy(UUID viewer, List<String> args) {
        if (args.size() == 1) {
            return Optional.of(request(viewer, "privacy", "", 1, "", -1L, Map.of()));
        }
        if (args.size() < 2 || args.size() > 3) {
            return Optional.empty();
        }
        Boolean optOut = switch (args.get(1).toLowerCase(Locale.ROOT)) {
            case "optout" -> Boolean.TRUE;
            case "optin" -> Boolean.FALSE;
            default -> null;
        };
        String token = args.size() == 3 ? args.get(2) : "";
        if (optOut == null || !validToken(token)) {
            return Optional.empty();
        }
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("in_optout", optOut.toString());
        return Optional.of(request(viewer, "privacy_save", token, 1, "", -1L, inputs));
    }

    private static boolean validToken(String token) {
        return token.isEmpty() || token.length() <= MAX_TOKEN_LENGTH;
    }

    private static UiRequest request(UUID viewer, String action, String token, int page,
                                     String uuid, long epoch, Map<String, String> extraInputs) {
        Map<String, String> inputs = new LinkedHashMap<>();
        if (!token.isEmpty()) {
            inputs.put("s", token);
        }
        inputs.put("p", Integer.toString(page));
        if (!uuid.isEmpty()) {
            inputs.put("t", uuid);
        }
        if (epoch >= 0L) {
            inputs.put("d", Long.toString(epoch));
        }
        inputs.putAll(extraInputs);
        return new UiRequest(viewer, action, token, page, uuid, epoch, inputs);
    }
}
