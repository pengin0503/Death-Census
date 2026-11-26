package com.pengin0503.deathcensus;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class DiscordLogic {
    /** Label strings used to render a digest in a given language. */
    public record DigestLabels(String header, String count, String topCauses, String mostDeaths) {
    }

    /** Realtime death notification PvP filter (DESIGN 1.3.0 §3, §12 Q8). */
    public enum PvpFilterMode {
        ALL,
        PVP_ONLY,
        OFF;

        /** The config value spelling used by {@code discordNotifyPvpMode}. */
        public String configId() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Parses an exact config value, or returns null when it is not one of the three. */
        public static PvpFilterMode fromConfigValue(String value) {
            if (value == null) {
                return null;
            }
            for (PvpFilterMode mode : values()) {
                if (mode.configId().equals(value)) {
                    return mode;
                }
            }
            return null;
        }
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

    /**
     * Pure realtime-death filter predicate (DESIGN 1.3.0 §3). A null or empty
     * dimension allow-list means every dimension; otherwise the stored id must
     * match one entry exactly (case-sensitive, as in {@code /dc history}).
     * PvP is {@code causeCategory.equals("player")} only — {@code killerType}
     * is deliberately not consulted because the category also covers indirect
     * player attribution (§12 Q7). The opt-out gate is not part of this
     * predicate: it stays in {@code DeathTracker}/{@code notifyDeath} so the
     * DESIGN §8 rules remain single-sourced. Digests, milestones and chat
     * announcements never call this method.
     */
    public static boolean shouldNotify(
            DeathEntry entry, Collection<String> allowedDimensions, PvpFilterMode mode) {
        if (entry == null) {
            return false;
        }
        if (allowedDimensions != null && !allowedDimensions.isEmpty()) {
            boolean dimensionAllowed = false;
            for (String allowed : allowedDimensions) {
                if (entry.dimension().equals(allowed)) {
                    dimensionAllowed = true;
                    break;
                }
            }
            if (!dimensionAllowed) {
                return false;
            }
        }
        PvpFilterMode effectiveMode = mode != null ? mode : PvpFilterMode.ALL;
        boolean playerCaused = "player".equals(entry.causeCategory());
        return switch (effectiveMode) {
            case PVP_ONLY -> playerCaused;
            case OFF -> !playerCaused;
            case ALL -> true;
        };
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

    /**
     * Resolves the webhook URL for a channel (DESIGN 1.3.0 §4). {@code REALTIME}
     * always resolves the primary value. {@code DIGEST} resolves the digest
     * value first and falls back to the primary one only when it resolves to an
     * empty string. A non-empty digest value is returned unchanged even when it
     * is syntactically invalid, so callers can disable the digest channel rather
     * than silently posting the digest to the wrong webhook (§12 question 9).
     * Both values still support {@code env:VAR} via {@link #resolveWebhookUrl}.
     */
    public static String resolveWebhookUrl(
            WebhookChannel channel, String primaryConfigured, String digestConfigured) {
        if (channel == WebhookChannel.DIGEST) {
            String digest = resolveWebhookUrl(digestConfigured);
            if (!digest.isEmpty()) {
                return digest;
            }
        }
        return resolveWebhookUrl(primaryConfigured);
    }
}
