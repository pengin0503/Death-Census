package com.pengin0503.deathcensus;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class DiscordLogic {
    /** Label strings used to render a digest in a given language. */
    public record DigestLabels(String header, String count, String topCauses, String mostDeaths) {
    }

    private static final DigestLabels ENGLISH_LABELS =
            new DigestLabels("Death Census digest", "death(s) since", "Top causes", "Most deaths");
    private static final DigestLabels JAPANESE_LABELS =
            new DigestLabels("死亡センサス ダイジェスト", "件の死亡（以降）", "主な死因", "最多死亡");

    private static final String PREFIX_DISCORD = "https://discord.com/api/webhooks/";
    private static final String PREFIX_DISCORDAPP = "https://discordapp.com/api/webhooks/";
    private static final String ENV_PREFIX = "env:";

    private DiscordLogic() {
    }

    public static String deathContent(DeathEntry e) {
        if (e == null) {
            return "";
        }
        String base = !e.deathMessage().isBlank()
                ? e.deathMessage()
                : e.playerName() + " died (" + e.causeCategory() + ")";
        return base
                + " — Day " + (e.gameDay() + 1)
                + " · " + e.dimension()
                + " · survived " + TimeFormat.formatDuration(e.survivalTicks());
    }

    /** Returns the digest labels for {@code lang}; anything other than Japanese is English. */
    public static DigestLabels labelsFor(String lang) {
        if (lang != null && lang.replace('_', '-').equalsIgnoreCase("ja-jp")) {
            return JAPANESE_LABELS;
        }
        return ENGLISH_LABELS;
    }

    public static String digestContent(List<PlayerDeaths> players, long sinceEpochMillis) {
        return digestContent(players, sinceEpochMillis, ZoneOffset.UTC, ENGLISH_LABELS);
    }

    public static String digestContent(
            List<PlayerDeaths> players,
            long sinceEpochMillis,
            ZoneId zone,
            DigestLabels labels
    ) {
        if (players == null || players.isEmpty()) {
            return "";
        }
        List<PlayerDeaths> recentPlayers = new ArrayList<>();
        int recentCount = 0;
        for (PlayerDeaths player : players) {
            if (player == null) {
                continue;
            }
            List<DeathEntry> recentDeaths = player.deaths().stream()
                    .filter(d -> d.epochMillis() > sinceEpochMillis)
                    .toList();
            if (!recentDeaths.isEmpty()) {
                recentCount += recentDeaths.size();
                recentPlayers.add(new PlayerDeaths(player.uuid(), player.name(), player.optOutPublic(), recentDeaths));
            }
        }
        if (recentCount == 0) {
            return "";
        }

        String header = labels.header() + ": " + recentCount + " " + labels.count() + " "
                + TimeFormat.formatDate(sinceEpochMillis, zone);

        String topCauses = CensusStats.causeCounts(recentPlayers, true).stream()
                .limit(3)
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .collect(Collectors.joining(", "));

        List<CensusStats.PlayerSummary> mostDeathsList = CensusStats.leaderboardDeaths(recentPlayers, false)
                .stream().limit(3).toList();

        StringBuilder result = new StringBuilder(header)
                .append('\n').append(labels.topCauses()).append(": ").append(topCauses);
        if (!mostDeathsList.isEmpty()) {
            String mostDeaths = mostDeathsList.stream()
                    .map(summary -> summary.name() + " (" + summary.deaths() + ")")
                    .collect(Collectors.joining(", "));
            result.append('\n').append(labels.mostDeaths()).append(": ").append(mostDeaths);
        }
        return result.toString();
    }

    public static boolean isValidWebhookUrl(String url) {
        if (url == null) {
            return false;
        }
        String remainder;
        if (url.startsWith(PREFIX_DISCORD)) {
            remainder = url.substring(PREFIX_DISCORD.length());
        } else if (url.startsWith(PREFIX_DISCORDAPP)) {
            remainder = url.substring(PREFIX_DISCORDAPP.length());
        } else {
            return false;
        }
        return !remainder.isBlank() && remainder.chars().noneMatch(Character::isWhitespace);
    }

    public static String resolveWebhookUrl(String configured) {
        if (configured == null) {
            return "";
        }
        String trimmed = configured.trim();
        if (trimmed.startsWith(ENV_PREFIX)) {
            String varName = trimmed.substring(ENV_PREFIX.length()).trim();
            if (varName.isEmpty()) {
                return "";
            }
            String value = System.getenv(varName);
            return (value == null || value.isBlank()) ? "" : value.trim();
        }
        return trimmed;
    }
}
