package com.pengin0503.deathcensus;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ExportRenderers {
    private static final String CSV_HEADER =
            "player_uuid,player_name,epoch_millis,datetime_iso,game_day,play_ticks,survival_ticks,"
                    + "dimension,x,y,z,cause_category,cause_id,killer_type,killer_name,"
                    + "game_mode,experience_level,death_message,excuse,last_words,f_count,witnesses";

    /** 1.4.0 PR-3 context columns, appended without altering the 1.3.0 prefix. */
    private static final String CSV_CONTEXT_HEADER =
            ",context_main_hand,context_armor,context_effects,context_biome,context_weather";

    private ExportRenderers() {
    }

    public static String toCsv(List<PlayerDeaths> players) {
        return renderCsv(players, true);
    }

    private static String renderCsv(List<PlayerDeaths> players, boolean includeContext) {
        List<DeathEntry> rows = new ArrayList<>();
        // Aggregates are never built from these copies; only the rendered rows are.
        List<PlayerDeaths> safe = safePlayerList(players);
        Map<String, String> witnessNames = WitnessRedactor.namesById(safe);
        Map<String, PlayerPrivacy> witnessPrivacy = WitnessRedactor.privacyById(safe);
        for (PlayerDeaths player : DeathRedactor.publicView(safe)) {
            rows.addAll(player.deaths());
        }
        rows.sort(Comparator.comparingLong(DeathEntry::epochMillis));

        StringBuilder sb = new StringBuilder(CSV_HEADER.length() + 1 + rows.size() * 128);
        sb.append(CSV_HEADER);
        if (includeContext) {
            sb.append(CSV_CONTEXT_HEADER);
        }
        sb.append('\n');
        for (DeathEntry d : rows) {
            String[] fields = {
                    d.playerUuid(),
                    d.playerName(),
                    Long.toString(d.epochMillis()),
                    Instant.ofEpochMilli(d.epochMillis()).toString(),
                    Long.toString(d.gameDay()),
                    Integer.toString(d.playTicks()),
                    Integer.toString(d.survivalTicks()),
                    d.dimension(),
                    csvCoordinate(d.x(), hiddenCoordinates(d)),
                    csvCoordinate(d.y(), hiddenCoordinates(d)),
                    csvCoordinate(d.z(), hiddenCoordinates(d)),
                    d.causeCategory(),
                    d.causeId(),
                    d.killerType(),
                    d.killerName(),
                    d.gameMode(),
                    Integer.toString(d.experienceLevel()),
                    d.deathMessage(),
                    d.excuse(),
                    d.lastWords(),
                    Integer.toString(d.fCount()),
                    WitnessRedactor.joinNames(WitnessRedactor.displayNames(
                            d, witnessNames, witnessPrivacy, false))
            };
            if (includeContext) {
                DeathContext c = d.context() == null ? DeathContext.EMPTY : d.context();
                String[] withContext = new String[fields.length + 5];
                System.arraycopy(fields, 0, withContext, 0, fields.length);
                withContext[fields.length] = c.mainHand();
                withContext[fields.length + 1] = String.join(", ", c.armor());
                withContext[fields.length + 2] = String.join(", ", c.effects());
                withContext[fields.length + 3] = c.biome();
                withContext[fields.length + 4] = c.weather();
                fields = withContext;
            }
            for (int i = 0; i < fields.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(TextEscaping.csvField(fields[i]));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** As {@link #toCsv(List)}, but rendered from a redacted copy when requested. */
    public static String toCsv(List<PlayerDeaths> players, boolean anonymize) {
        return toCsv(players, anonymize, true);
    }

    /** As above, honoring the {@code deathContextInExports} config switch. */
    public static String toCsv(List<PlayerDeaths> players, boolean anonymize, boolean includeContext) {
        return renderCsv(anonymize ? Anonymizer.apply(players) : players, includeContext);
    }

    public static String toJson(List<PlayerDeaths> players, long generatedAtEpochMillis) {
        return renderJson(players, generatedAtEpochMillis, false, false);
    }

    /** As {@link #toJson(List, long)}, with anonymized metadata and a redacted copy when requested. */
    public static String toJson(List<PlayerDeaths> players, long generatedAtEpochMillis, boolean anonymize) {
        return toJson(players, generatedAtEpochMillis, anonymize, true);
    }

    /** As above, honoring the {@code deathContextInExports} config switch. */
    public static String toJson(List<PlayerDeaths> players, long generatedAtEpochMillis,
                                boolean anonymize, boolean includeContext) {
        return renderJson(anonymize ? Anonymizer.apply(players) : players,
                generatedAtEpochMillis, anonymize, false, List.of(), includeContext);
    }

    /**
     * Backup-only JSON: identical to the public export plus the §9 vanilla
     * baseline fields, so backup/restore preserves them without changing any
     * HTML/JSON/CSV export format.
     */
    public static String toBackupJson(List<PlayerDeaths> players, long generatedAtEpochMillis) {
        return toBackupJson(players, generatedAtEpochMillis, List.of());
    }

    /**
     * As above, including the §13.4 chronicle aggregate. 1.3.0 backups omit
     * every 1.4.0 field; the parser defaults them to empty.
     */
    public static String toBackupJson(List<PlayerDeaths> players, long generatedAtEpochMillis,
                                      List<ChronicleEvent> chronicleEvents) {
        return renderJson(players, generatedAtEpochMillis, false, true, chronicleEvents, true);
    }

    private static String renderJson(List<PlayerDeaths> players, long generatedAtEpochMillis,
                                     boolean anonymized, boolean includeBaselines) {
        return renderJson(players, generatedAtEpochMillis, anonymized, includeBaselines, List.of(), true);
    }

    private static String renderJson(List<PlayerDeaths> players, long generatedAtEpochMillis,
                                     boolean anonymized, boolean includeBaselines,
                                     List<ChronicleEvent> chronicleEvents, boolean includeContext) {
        List<PlayerDeaths> safePlayers = safePlayerList(players);
        // Totals below keep using safePlayers (unredacted, DESIGN §8); only the
        // per-death rows use the public redaction view.
        List<PlayerDeaths> renderPlayers = DeathRedactor.publicView(safePlayers);
        int totalDeaths = CensusStats.totalDeaths(safePlayers);
        Map<String, String> witnessNames = WitnessRedactor.namesById(safePlayers);
        Map<String, PlayerPrivacy> witnessPrivacy = WitnessRedactor.privacyById(safePlayers);

        StringBuilder sb = new StringBuilder(256 + totalDeaths * 256);
        sb.append("{\n");
        sb.append("  \"generatedAt\": ").append(generatedAtEpochMillis).append(",\n");
        if (anonymized) {
            sb.append("  \"anonymized\": true,\n");
        }
        sb.append("  \"totalDeaths\": ").append(totalDeaths).append(",\n");
        if (includeBaselines) {
            appendChronicleEvents(sb, ChronicleEvent.copyOf(chronicleEvents), "  ");
            sb.append(",\n");
        }
        sb.append("  \"players\": [");
        if (!safePlayers.isEmpty()) {
            sb.append('\n');
            for (int i = 0; i < safePlayers.size(); i++) {
                PlayerDeaths p = safePlayers.get(i);
                sb.append("    {\n");
                sb.append("      \"uuid\": \"").append(TextEscaping.jsonString(p.uuid())).append("\",\n");
                sb.append("      \"name\": \"").append(TextEscaping.jsonString(p.name())).append("\",\n");
                sb.append("      \"optOutPublic\": ").append(p.optOutPublic()).append(",\n");
                sb.append("      \"hideCoordinates\": ").append(p.hideCoordinates()).append(",\n");
                sb.append("      \"hidePvpOpponent\": ").append(p.hidePvpOpponent()).append(",\n");
                sb.append("      \"discordOptOut\": ").append(p.discordOptOut()).append(",\n");
                if (includeBaselines) {
                    sb.append("      \"vanillaDeathsBaseline\": ").append(p.vanillaDeathsBaseline()).append(",\n");
                    sb.append("      \"vanillaBaselineCauses\": {");
                    boolean firstCause = true;
                    for (Map.Entry<String, Integer> cause : p.vanillaBaselineCauses().entrySet()) {
                        if (!firstCause) {
                            sb.append(',');
                        }
                        firstCause = false;
                        sb.append('"').append(TextEscaping.jsonString(cause.getKey()))
                                .append("\": ").append(cause.getValue());
                    }
                    sb.append("},\n");
                }
                sb.append("      \"deaths\": [");
                List<DeathEntry> deaths = renderPlayers.get(i).deaths();
                if (!deaths.isEmpty()) {
                    sb.append('\n');
                    for (int j = 0; j < deaths.size(); j++) {
                        DeathEntry d = deaths.get(j);
                        sb.append("        {\n");
                        sb.append("          \"playerUuid\": \"").append(TextEscaping.jsonString(d.playerUuid())).append("\",\n");
                        sb.append("          \"playerName\": \"").append(TextEscaping.jsonString(d.playerName())).append("\",\n");
                        sb.append("          \"epochMillis\": ").append(d.epochMillis()).append(",\n");
                        sb.append("          \"gameDay\": ").append(d.gameDay()).append(",\n");
                        sb.append("          \"playTicks\": ").append(d.playTicks()).append(",\n");
                        sb.append("          \"survivalTicks\": ").append(d.survivalTicks()).append(",\n");
                        sb.append("          \"dimension\": \"").append(TextEscaping.jsonString(d.dimension())).append("\",\n");
                        sb.append("          \"x\": ").append(jsonCoordinate(d.x(), hiddenCoordinates(d))).append(",\n");
                        sb.append("          \"y\": ").append(jsonCoordinate(d.y(), hiddenCoordinates(d))).append(",\n");
                        sb.append("          \"z\": ").append(jsonCoordinate(d.z(), hiddenCoordinates(d))).append(",\n");
                        sb.append("          \"causeCategory\": \"").append(TextEscaping.jsonString(d.causeCategory())).append("\",\n");
                        sb.append("          \"causeId\": \"").append(TextEscaping.jsonString(d.causeId())).append("\",\n");
                        sb.append("          \"deathMessage\": \"").append(TextEscaping.jsonString(d.deathMessage())).append("\",\n");
                        sb.append("          \"killerType\": \"").append(TextEscaping.jsonString(d.killerType())).append("\",\n");
                        sb.append("          \"killerName\": \"").append(TextEscaping.jsonString(d.killerName())).append("\",\n");
                        sb.append("          \"gameMode\": \"").append(TextEscaping.jsonString(d.gameMode())).append("\",\n");
                        sb.append("          \"experienceLevel\": ").append(d.experienceLevel()).append(",\n");
                        sb.append("          \"excuse\": \"").append(TextEscaping.jsonString(d.excuse())).append("\",\n");
                        sb.append("          \"lastWords\": \"").append(TextEscaping.jsonString(d.lastWords())).append("\",\n");
                        if (includeBaselines) {
                            sb.append("          \"fCount\": ").append(d.fCount()).append(",\n");
                            appendWitnesses(sb, d.witnesses(), "          ");
                            sb.append(",\n");
                            // Backups are operator-only: preserve the stored biome even
                            // when the public render view redacts it for hideCoordinates.
                            DeathContext stored = safePlayers.get(i).deaths().get(j).context();
                            appendContext(sb, stored, "          ");
                            sb.append('\n');
                        } else {
                            sb.append("          \"fCount\": ").append(d.fCount()).append(",\n");
                            appendWitnessNames(sb, WitnessRedactor.displayNames(
                                    d, witnessNames, witnessPrivacy, false), "          ");
                            if (includeContext) {
                                sb.append(",\n");
                                appendContext(sb, d.context(), "          ");
                            }
                            sb.append('\n');
                        }
                        sb.append("        }");
                        if (j + 1 < deaths.size()) {
                            sb.append(',');
                        }
                        sb.append('\n');
                    }
                    sb.append("      ]");
                } else {
                    sb.append(']');
                }
                if (includeBaselines) {
                    sb.append(",\n");
                    appendNearDeaths(sb, safePlayers.get(i).nearDeaths(), "      ");
                }
                sb.append('\n');
                sb.append("    }");
                if (i + 1 < safePlayers.size()) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            sb.append("  ]");
        } else {
            sb.append(']');
        }
        sb.append('\n');
        sb.append("}\n");
        return sb.toString();
    }

    public static String toHtml(List<PlayerDeaths> players, long generatedAtEpochMillis, ZoneId zone) {
        return renderHtml(players, generatedAtEpochMillis, zone, true, "Context");
    }

    /** As above, honoring the {@code deathContextInExports} config switch. */
    public static String toHtml(List<PlayerDeaths> players, long generatedAtEpochMillis, ZoneId zone,
                                boolean anonymize, boolean includeContext) {
        return toHtml(players, generatedAtEpochMillis, zone, anonymize, includeContext, "Context");
    }

    /** As above with a caller-supplied (localized) column header. */
    public static String toHtml(List<PlayerDeaths> players, long generatedAtEpochMillis, ZoneId zone,
                                boolean anonymize, boolean includeContext, String contextHeader) {
        List<PlayerDeaths> safePlayers = anonymize ? Anonymizer.apply(players) : safePlayerList(players);
        return renderHtml(safePlayers, generatedAtEpochMillis, zone, includeContext,
                contextHeader == null || contextHeader.isBlank() ? "Context" : contextHeader);
    }

    private static String renderHtml(List<PlayerDeaths> players, long generatedAtEpochMillis, ZoneId zone,
                                     boolean includeContext, String contextHeader) {
        List<PlayerDeaths> safePlayers = safePlayerList(players);
        // Summaries and rankings stay unredacted (DESIGN §8); timelines render
        // the public redaction view of the same records.
        List<PlayerDeaths> renderPlayers = DeathRedactor.publicView(safePlayers);
        int totalDeaths = CensusStats.totalDeaths(safePlayers);
        Map<String, String> witnessNames = WitnessRedactor.namesById(safePlayers);
        Map<String, PlayerPrivacy> witnessPrivacy = WitnessRedactor.privacyById(safePlayers);
        List<Map.Entry<String, Integer>> causes = CensusStats.causeCounts(safePlayers, true);
        List<CensusStats.PlayerSummary> leaderboard = CensusStats.leaderboardDeaths(safePlayers, true);
        int topLimit = Math.min(10, leaderboard.size());

        StringBuilder sb = new StringBuilder(2048);
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html lang=\"en\">\n");
        sb.append("<head>\n");
        sb.append("<meta charset=\"UTF-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        sb.append("<title>Death Census</title>\n");
        sb.append("<style>\n");
        sb.append("body { font-family: system-ui, -apple-system, sans-serif; max-width: 1100px; margin: 2rem auto; padding: 0 1rem; color: #1f2937; background: #f9fafb; }\n");
        sb.append("h1, h2, h3 { color: #111827; }\n");
        sb.append(".meta { color: #4b5563; margin-bottom: 1.5rem; }\n");
        sb.append(".summary { display: flex; gap: 1.5rem; margin-bottom: 2rem; }\n");
        sb.append(".card { background: #ffffff; border: 1px solid #e5e7eb; border-radius: 8px; padding: 1rem 1.25rem; }\n");
        sb.append("table { width: 100%; border-collapse: collapse; background: #ffffff; margin-bottom: 2rem; }\n");
        sb.append("th, td { border: 1px solid #e5e7eb; padding: 0.5rem 0.75rem; text-align: left; }\n");
        sb.append("th { background: #f3f4f6; font-weight: 600; }\n");
        sb.append("</style>\n");
        sb.append("</head>\n");
        sb.append("<body>\n");

        sb.append("<h1>Death Census</h1>\n");
        sb.append("<p class=\"meta\">Generated: ")
                .append(TextEscaping.html(TimeFormat.formatDateTime(generatedAtEpochMillis, zone)))
                .append("</p>\n");

        sb.append("<section class=\"summary\">\n");
        sb.append("<div class=\"card\"><strong>Total Deaths:</strong> ").append(totalDeaths).append("</div>\n");
        sb.append("<div class=\"card\"><strong>Players:</strong> ").append(safePlayers.size()).append("</div>\n");
        sb.append("</section>\n");

        sb.append("<section>\n");
        sb.append("<h2>Cause Statistics</h2>\n");
        sb.append("<table>\n");
        sb.append("<thead><tr><th>Cause</th><th>Count</th><th>Share</th></tr></thead>\n");
        sb.append("<tbody>\n");
        for (Map.Entry<String, Integer> entry : causes) {
            double pct = totalDeaths > 0 ? (entry.getValue() * 100.0) / totalDeaths : 0.0;
            sb.append("<tr><td>")
                    .append(TextEscaping.html(entry.getKey()))
                    .append("</td><td>")
                    .append(entry.getValue())
                    .append("</td><td>")
                    .append(String.format(Locale.ROOT, "%.1f%%", pct))
                    .append("</td></tr>\n");
        }
        sb.append("</tbody>\n");
        sb.append("</table>\n");
        sb.append("</section>\n");

        sb.append("<section>\n");
        sb.append("<h2>Leaderboard (Top 10 Deaths)</h2>\n");
        sb.append("<table>\n");
        sb.append("<thead><tr><th>#</th><th>Player</th><th>Deaths</th><th>Longest Survival</th><th>Top Cause</th></tr></thead>\n");
        sb.append("<tbody>\n");
        for (int i = 0; i < topLimit; i++) {
            CensusStats.PlayerSummary summary = leaderboard.get(i);
            sb.append("<tr><td>").append(i + 1)
                    .append("</td><td>").append(TextEscaping.html(summary.name()))
                    .append("</td><td>").append(summary.deaths())
                    .append("</td><td>").append(TextEscaping.html(TimeFormat.formatDuration(summary.longestSurvivalTicks())))
                    .append("</td><td>").append(TextEscaping.html(summary.topCategory()))
                    .append("</td></tr>\n");
        }
        sb.append("</tbody>\n");
        sb.append("</table>\n");
        sb.append("</section>\n");

        sb.append("<section>\n");
        sb.append("<h2>Player Timelines</h2>\n");
        for (PlayerDeaths player : renderPlayers) {
            sb.append("<article>\n");
            sb.append("<h3>").append(TextEscaping.html(player.name())).append("</h3>\n");
            sb.append("<p>Deaths: ").append(player.deaths().size()).append("</p>\n");
            sb.append("<table>\n");
            sb.append("<thead><tr><th>Date/Time</th><th>Day</th><th>Dimension</th><th>Position</th><th>Cause</th><th>Death Message</th><th>Survived</th><th>Witnesses</th>");
            if (includeContext) {
                sb.append("<th>").append(TextEscaping.html(contextHeader)).append("</th>");
            }
            sb.append("</tr></thead>\n");
            sb.append("<tbody>\n");
            for (DeathEntry d : player.deaths()) {
                String position = hiddenCoordinates(d)
                        ? DeathRedactor.COORDINATE_MARKER + ", " + DeathRedactor.COORDINATE_MARKER
                                + ", " + DeathRedactor.COORDINATE_MARKER
                        : String.format(Locale.ROOT, "%.1f, %.1f, %.1f", d.x(), d.y(), d.z());
                sb.append("<tr><td>")
                        .append(TextEscaping.html(TimeFormat.formatDateTime(d.epochMillis(), zone)))
                        .append("</td><td>")
                        .append(d.gameDay() + 1)
                        .append("</td><td>")
                        .append(TextEscaping.html(d.dimension()))
                        .append("</td><td>")
                        .append(TextEscaping.html(position))
                        .append("</td><td>")
                        .append(TextEscaping.html(d.causeCategory()))
                        .append("</td><td>")
                        .append(TextEscaping.html(d.deathMessage()));
                if (!d.excuse().isEmpty()) {
                    sb.append("<br><small>Excuse: ")
                            .append(TextEscaping.html("\"" + d.excuse() + "\""))
                            .append("</small>");
                }
                if (!d.lastWords().isEmpty()) {
                    sb.append("<br><small>Last words: ")
                            .append(TextEscaping.html("\"" + d.lastWords() + "\""))
                            .append("</small>");
                }
                if (d.fCount() > 0) {
                    sb.append("<br><small>F x").append(d.fCount()).append("</small>");
                }
                sb.append("</td><td>")
                        .append(TextEscaping.html(TimeFormat.formatDuration(d.survivalTicks())))
                        .append("</td><td>")
                        .append(TextEscaping.html(WitnessRedactor.joinNames(WitnessRedactor.displayNames(
                                d, witnessNames, witnessPrivacy, false))))
                        .append("</td>");
                if (includeContext) {
                    DeathContext c = d.context() == null ? DeathContext.EMPTY : d.context();
                    sb.append("<td>").append(TextEscaping.html(contextText(c))).append("</td>");
                }
                sb.append("</tr>\n");
            }
            sb.append("</tbody>\n");
            sb.append("</table>\n");
            sb.append("</article>\n");
        }
        sb.append("</section>\n");

        sb.append("</body>\n");
        sb.append("</html>\n");
        return sb.toString();
    }

    /** As {@link #toHtml(List, long, ZoneId)}, but rendered from a redacted copy when requested. */
    public static String toHtml(List<PlayerDeaths> players, long generatedAtEpochMillis, ZoneId zone,
                                boolean anonymize) {
        return toHtml(players, generatedAtEpochMillis, zone, anonymize, true);
    }

    private static List<PlayerDeaths> safePlayerList(List<PlayerDeaths> players) {
        if (players == null) {
            return List.of();
        }
        List<PlayerDeaths> result = new ArrayList<>(players.size());
        for (PlayerDeaths player : players) {
            if (player != null) {
                result.add(player);
            }
        }
        return result;
    }

    private static String jsonNumber(double value) {
        return Double.isFinite(value) ? Double.toString(value) : "0";
    }

    private static boolean hiddenCoordinates(DeathEntry entry) {
        return DeathRedactor.isCoordinateHidden(entry);
    }

    /** Escaped-on-render, language-neutral context summary for HTML/surfaces. */
    public static String contextText(DeathContext context) {
        DeathContext c = context == null ? DeathContext.EMPTY : context;
        if (c.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(96);
        appendPart(sb, "Main hand", c.mainHand());
        appendPart(sb, "Armor", String.join(", ", c.armor()));
        appendPart(sb, "Effects", String.join(", ", c.effects()));
        appendPart(sb, "Biome", c.biome());
        appendPart(sb, "Weather", c.weather());
        return sb.toString();
    }

    private static void appendPart(StringBuilder sb, String label, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(" · ");
        }
        sb.append(label).append(": ").append(value);
    }

    private static String csvCoordinate(double value, boolean hidden) {
        return hidden ? DeathRedactor.COORDINATE_MARKER : String.format(Locale.ROOT, "%.1f", value);
    }

    private static String jsonCoordinate(double value, boolean hidden) {
        return hidden
                ? "\"" + DeathRedactor.COORDINATE_MARKER + "\""
                : jsonNumber(value);
    }

    // Backup-only 1.4.0 aggregates (§13.2.4). Public HTML/JSON/CSV never call
    // these, and old backups simply omit the keys.

    /** Public JSON: witness display names only, already composed for a public viewer. */
    private static void appendWitnessNames(StringBuilder sb, List<String> names, String indent) {
        sb.append(indent).append("\"witnesses\": [");
        boolean first = true;
        for (String name : names) {
            if (name == null) {
                continue;
            }
            sb.append(first ? "\"" : ", \"").append(TextEscaping.jsonString(name)).append('"');
            first = false;
        }
        sb.append(']');
    }

    private static void appendWitnesses(StringBuilder sb, List<WitnessRef> witnesses, String indent) {
        List<WitnessRef> refs = WitnessRef.copyOf(witnesses);
        sb.append(indent).append("\"witnesses\": [");
        boolean first = true;
        for (WitnessRef w : refs) {
            sb.append(first ? "\n" : ",\n").append(indent).append("  {\n")
                    .append(indent).append("    \"uuid\": \"").append(TextEscaping.jsonString(w.uuid())).append("\",\n")
                    .append(indent).append("    \"nameAtDeath\": \"")
                    .append(TextEscaping.jsonString(w.nameAtDeath())).append("\"\n")
                    .append(indent).append("  }");
            first = false;
        }
        if (!first) {
            sb.append('\n').append(indent);
        }
        sb.append(']');
    }

    private static void appendContext(StringBuilder sb, DeathContext context, String indent) {
        DeathContext c = context == null ? DeathContext.EMPTY : context;
        sb.append(indent).append("\"context\": {\n")
                .append(indent).append("  \"mainHand\": \"").append(TextEscaping.jsonString(c.mainHand())).append("\",\n")
                .append(indent).append("  \"armor\": [");
        appendStrings(sb, c.armor());
        sb.append("],\n").append(indent).append("  \"effects\": [");
        appendStrings(sb, c.effects());
        sb.append("],\n").append(indent).append("  \"biome\": \"")
                .append(TextEscaping.jsonString(c.biome())).append("\",\n")
                .append(indent).append("  \"weather\": \"")
                .append(TextEscaping.jsonString(c.weather())).append("\"\n")
                .append(indent).append('}');
    }

    private static void appendStrings(StringBuilder sb, List<String> values) {
        boolean first = true;
        for (String value : values) {
            if (value == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(TextEscaping.jsonString(value)).append('"');
        }
    }

    private static void appendNearDeaths(StringBuilder sb, List<NearDeathEvent> events, String indent) {
        List<NearDeathEvent> clean = NearDeathEvent.copyOf(events);
        sb.append(indent).append("\"nearDeaths\": [");
        boolean first = true;
        for (NearDeathEvent e : clean) {
            sb.append(first ? "\n" : ",\n").append(indent).append("  {\n")
                    .append(indent).append("    \"uuid\": \"").append(TextEscaping.jsonString(e.uuid())).append("\",\n")
                    .append(indent).append("    \"name\": \"").append(TextEscaping.jsonString(e.name())).append("\",\n")
                    .append(indent).append("    \"epochMillis\": ").append(e.epochMillis()).append(",\n")
                    .append(indent).append("    \"dimension\": \"").append(TextEscaping.jsonString(e.dimension())).append("\",\n")
                    .append(indent).append("    \"x\": ").append(jsonNumber(e.x())).append(",\n")
                    .append(indent).append("    \"y\": ").append(jsonNumber(e.y())).append(",\n")
                    .append(indent).append("    \"z\": ").append(jsonNumber(e.z())).append(",\n")
                    .append(indent).append("    \"causeCategory\": \"").append(TextEscaping.jsonString(e.causeCategory())).append("\",\n")
                    .append(indent).append("    \"causeId\": \"").append(TextEscaping.jsonString(e.causeId())).append("\",\n")
                    .append(indent).append("    \"kind\": \"").append(e.kind().name()).append("\"\n")
                    .append(indent).append("  }");
            first = false;
        }
        if (!first) {
            sb.append('\n').append(indent);
        }
        sb.append(']');
    }

    private static void appendChronicleEvents(StringBuilder sb, List<ChronicleEvent> events, String indent) {
        List<ChronicleEvent> clean = ChronicleEvent.copyOf(events);
        sb.append(indent).append("\"chronicleEvents\": [");
        boolean first = true;
        for (ChronicleEvent e : clean) {
            sb.append(first ? "\n" : ",\n").append(indent).append("  {\n")
                    .append(indent).append("    \"epochMillis\": ").append(e.epochMillis()).append(",\n")
                    .append(indent).append("    \"gameDay\": ").append(e.gameDay()).append(",\n")
                    .append(indent).append("    \"kind\": \"").append(e.kind().name()).append("\",\n")
                    .append(indent).append("    \"uuid\": \"").append(TextEscaping.jsonString(e.uuid())).append("\",\n")
                    .append(indent).append("    \"name\": \"").append(TextEscaping.jsonString(e.name())).append("\",\n")
                    .append(indent).append("    \"dimension\": \"").append(TextEscaping.jsonString(e.dimension())).append("\",\n")
                    .append(indent).append("    \"causeCategory\": \"").append(TextEscaping.jsonString(e.causeCategory())).append("\",\n")
                    .append(indent).append("    \"count\": ").append(e.count()).append(",\n")
                    .append(indent).append("    \"reconstructed\": ").append(e.reconstructed()).append('\n')
                    .append(indent).append("  }");
            first = false;
        }
        if (!first) {
            sb.append('\n').append(indent);
        }
        sb.append(']');
    }
}
