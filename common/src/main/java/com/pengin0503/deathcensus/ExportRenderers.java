package com.pengin0503.deathcensus;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ExportRenderers {
    private static final String CSV_HEADER =
            "player_uuid,player_name,epoch_millis,datetime_iso,game_day,play_ticks,survival_ticks,"
                    + "dimension,x,y,z,cause_category,cause_id,killer_type,killer_name,"
                    + "game_mode,experience_level,death_message,excuse,last_words,f_count";

    private ExportRenderers() {
    }

    public static String toCsv(List<PlayerDeaths> players) {
        return toCsv(players, null);
    }

    /**
     * As {@link #toCsv(List)}, but scrubs every snapshot-wide hidden identity
     * (review S3) from the rendered rows. The full snapshot is the dictionary
     * source; {@code players} only filters which records are exported.
     */
    public static String toCsv(List<PlayerDeaths> players, Collection<PlayerDeaths> fullSnapshot) {
        HiddenIdentities hidden = HiddenIdentities.of(fullSnapshot);
        List<DeathEntry> rows = new ArrayList<>();
        if (players != null) {
            for (PlayerDeaths player : players) {
                if (player != null) {
                    for (DeathEntry death : player.deaths()) {
                        rows.add(hidden.scrub(death));
                    }
                }
            }
        }
        rows.sort(Comparator.comparingLong(DeathEntry::epochMillis));

        StringBuilder sb = new StringBuilder(CSV_HEADER.length() + 1 + rows.size() * 128);
        sb.append(CSV_HEADER).append('\n');
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
                    String.format(Locale.ROOT, "%.1f", d.x()),
                    String.format(Locale.ROOT, "%.1f", d.y()),
                    String.format(Locale.ROOT, "%.1f", d.z()),
                    d.causeCategory(),
                    d.causeId(),
                    d.killerType(),
                    d.killerName(),
                    d.gameMode(),
                    Integer.toString(d.experienceLevel()),
                    d.deathMessage(),
                    d.excuse(),
                    d.lastWords(),
                    Integer.toString(d.fCount())
            };
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

    public static String toJson(List<PlayerDeaths> players, long generatedAtEpochMillis) {
        return renderJson(players, generatedAtEpochMillis, HiddenIdentities.empty());
    }

    /**
     * As {@link #toJson(List, long)}, but scrubs snapshot-wide hidden identities
     * (review S3) from the rendered rows. The full snapshot is the dictionary
     * source; {@code players} only filters which records are exported.
     */
    public static String toJson(List<PlayerDeaths> players, long generatedAtEpochMillis,
                                Collection<PlayerDeaths> fullSnapshot) {
        return renderJson(players, generatedAtEpochMillis, HiddenIdentities.of(fullSnapshot));
    }

    /** Backup JSON keeps the raw records: backups are not a public projection. */
    public static String toBackupJson(List<PlayerDeaths> players, long generatedAtEpochMillis) {
        return renderJson(players, generatedAtEpochMillis, HiddenIdentities.empty());
    }

    private static String renderJson(List<PlayerDeaths> players, long generatedAtEpochMillis,
                                     HiddenIdentities hidden) {
        List<PlayerDeaths> safePlayers = safePlayerList(players);
        int totalDeaths = CensusStats.totalDeaths(safePlayers);

        StringBuilder sb = new StringBuilder(256 + totalDeaths * 256);
        sb.append("{\n");
        sb.append("  \"generatedAt\": ").append(generatedAtEpochMillis).append(",\n");
        sb.append("  \"totalDeaths\": ").append(totalDeaths).append(",\n");
        sb.append("  \"players\": [");
        if (!safePlayers.isEmpty()) {
            sb.append('\n');
            for (int i = 0; i < safePlayers.size(); i++) {
                PlayerDeaths p = safePlayers.get(i);
                sb.append("    {\n");
                sb.append("      \"uuid\": \"").append(TextEscaping.jsonString(p.uuid())).append("\",\n");
                sb.append("      \"name\": \"").append(TextEscaping.jsonString(p.name())).append("\",\n");
                sb.append("      \"optOutPublic\": ").append(p.optOutPublic()).append(",\n");
                sb.append("      \"deaths\": [");
                List<DeathEntry> deaths = p.deaths();
                if (!deaths.isEmpty()) {
                    sb.append('\n');
                    for (int j = 0; j < deaths.size(); j++) {
                        DeathEntry d = hidden.scrub(deaths.get(j));
                        sb.append("        {\n");
                        sb.append("          \"playerUuid\": \"").append(TextEscaping.jsonString(d.playerUuid())).append("\",\n");
                        sb.append("          \"playerName\": \"").append(TextEscaping.jsonString(d.playerName())).append("\",\n");
                        sb.append("          \"epochMillis\": ").append(d.epochMillis()).append(",\n");
                        sb.append("          \"gameDay\": ").append(d.gameDay()).append(",\n");
                        sb.append("          \"playTicks\": ").append(d.playTicks()).append(",\n");
                        sb.append("          \"survivalTicks\": ").append(d.survivalTicks()).append(",\n");
                        sb.append("          \"dimension\": \"").append(TextEscaping.jsonString(d.dimension())).append("\",\n");
                        sb.append("          \"x\": ").append(jsonNumber(d.x())).append(",\n");
                        sb.append("          \"y\": ").append(jsonNumber(d.y())).append(",\n");
                        sb.append("          \"z\": ").append(jsonNumber(d.z())).append(",\n");
                        sb.append("          \"causeCategory\": \"").append(TextEscaping.jsonString(d.causeCategory())).append("\",\n");
                        sb.append("          \"causeId\": \"").append(TextEscaping.jsonString(d.causeId())).append("\",\n");
                        sb.append("          \"deathMessage\": \"").append(TextEscaping.jsonString(d.deathMessage())).append("\",\n");
                        sb.append("          \"killerType\": \"").append(TextEscaping.jsonString(d.killerType())).append("\",\n");
                        sb.append("          \"killerName\": \"").append(TextEscaping.jsonString(d.killerName())).append("\",\n");
                        sb.append("          \"gameMode\": \"").append(TextEscaping.jsonString(d.gameMode())).append("\",\n");
                        sb.append("          \"experienceLevel\": ").append(d.experienceLevel()).append(",\n");
                        sb.append("          \"excuse\": \"").append(TextEscaping.jsonString(d.excuse())).append("\",\n");
                        sb.append("          \"lastWords\": \"").append(TextEscaping.jsonString(d.lastWords())).append("\",\n");
                        sb.append("          \"fCount\": ").append(d.fCount()).append('\n');
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
        return renderHtml(players, generatedAtEpochMillis, zone, HiddenIdentities.empty());
    }

    /**
     * As {@link #toHtml(List, long, ZoneId)}, but scrubs snapshot-wide hidden
     * identities (review S3) from the rendered timeline rows. The full snapshot
     * is the dictionary source; {@code players} only filters what is exported.
     */
    public static String toHtml(List<PlayerDeaths> players, long generatedAtEpochMillis, ZoneId zone,
                                Collection<PlayerDeaths> fullSnapshot) {
        return renderHtml(players, generatedAtEpochMillis, zone, HiddenIdentities.of(fullSnapshot));
    }

    private static String renderHtml(List<PlayerDeaths> players, long generatedAtEpochMillis, ZoneId zone,
                                     HiddenIdentities hidden) {
        List<PlayerDeaths> safePlayers = safePlayerList(players);
        int totalDeaths = CensusStats.totalDeaths(safePlayers);
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
        for (PlayerDeaths player : safePlayers) {
            sb.append("<article>\n");
            sb.append("<h3>").append(TextEscaping.html(player.name())).append("</h3>\n");
            sb.append("<p>Deaths: ").append(player.deaths().size()).append("</p>\n");
            sb.append("<table>\n");
            sb.append("<thead><tr><th>Date/Time</th><th>Day</th><th>Dimension</th><th>Position</th><th>Cause</th><th>Death Message</th><th>Survived</th></tr></thead>\n");
            sb.append("<tbody>\n");
            for (DeathEntry raw : player.deaths()) {
                DeathEntry d = hidden.scrub(raw);
                String position = String.format(Locale.ROOT, "%.1f, %.1f, %.1f", d.x(), d.y(), d.z());
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
                        .append("</td></tr>\n");
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
}
