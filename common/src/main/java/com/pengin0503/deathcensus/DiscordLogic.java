package com.pengin0503.deathcensus;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class DiscordLogic {
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
                + " — Day " + e.gameDay()
                + " · " + e.dimension()
                + " · survived " + TimeFormat.formatDuration(e.survivalTicks());
    }

    public static String digestContent(List<PlayerDeaths> players, long sinceEpochMillis) {
        if (players == null || players.isEmpty()) {
            return "";
        }
        List<PlayerDeaths> filtered = new ArrayList<>();
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
                filtered.add(new PlayerDeaths(player.uuid(), player.name(), player.optOutPublic(), recentDeaths));
            }
        }
        if (recentCount == 0) {
            return "";
        }

        String header = "Death Census digest: " + recentCount + " death(s) since "
                + TimeFormat.formatDate(sinceEpochMillis, ZoneOffset.UTC);

        String topCauses = CensusStats.causeCounts(filtered, true).stream()
                .limit(3)
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .collect(Collectors.joining(", "));

        String mostDeaths = CensusStats.leaderboardDeaths(filtered, true).stream()
                .limit(3)
                .map(summary -> summary.name() + " (" + summary.deaths() + ")")
                .collect(Collectors.joining(", "));

        return header + "\nTop causes: " + topCauses + "\nMost deaths: " + mostDeaths;
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
