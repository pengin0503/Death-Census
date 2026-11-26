package com.pengin0503.deathcensus;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class DiscordLogic {
    /** Label strings used to render a digest in a given language. */
    public record DigestLabels(String header, String count, String topCauses, String mostDeaths) {
    }

    /**
     * Season-specific label templates (DESIGN 1.3.0 §5). Version code fills this
     * record from {@code discord.deathcensus.season.*} lang keys so the Discord
     * language setting keeps working on dedicated servers; the constants below
     * are the default/English fallback for pure callers.
     */
    public record SeasonLabels(
            String header,
            String headerField,
            String lastWords,
            String awards,
            String allCategories,
            String longestSurvival,
            String rarestCause,
            String none
    ) {
        public SeasonLabels {
            header = text(header);
            headerField = text(headerField);
            lastWords = text(lastWords);
            awards = text(awards);
            allCategories = text(allCategories);
            longestSurvival = text(longestSurvival);
            rarestCause = text(rarestCause);
            none = text(none);
        }

        public static SeasonLabels english() {
            return new SeasonLabels(
                    "Season digest: %1$s death(s), %2$s → %3$s",
                    "Season digest",
                    "Most-respected last words (by F count)",
                    "Awards",
                    "All 17 categories: %1$s (%2$s qualifiers)",
                    "Longest survival: %1$s",
                    "Rarest cause: %1$s",
                    "none");
        }

        public static SeasonLabels japanese() {
            return new SeasonLabels(
                    "シーズンダイジェスト: %1$s 件の死亡（%2$s → %3$s）",
                    "シーズンダイジェスト",
                    "印象的な最期の言葉（F数順）",
                    "表彰",
                    "全17カテゴリ: %1$s（%2$s人）",
                    "最長生存: %1$s",
                    "最も珍しい死因: %1$s",
                    "なし");
        }

        private static String text(String value) {
            return value == null ? "" : value;
        }
    }

    /**
     * Data for the season award lines. Completers come from the version-side
     * {@code PlayerCensus.lifetimeCategories}; the other two are derived from
     * the deaths in the requested range (public players only for named awards).
     */
    public record SeasonAwards(List<String> allCategoryCompleters, DeathEntry longestSurvival, String rarestCause) {
        public SeasonAwards {
            allCategoryCompleters = allCategoryCompleters == null
                    ? List.of()
                    : allCategoryCompleters.stream()
                            .filter(name -> name != null && !name.isBlank())
                            .toList();
            rarestCause = rarestCause == null ? "" : rarestCause;
        }

        public static SeasonAwards none() {
            return new SeasonAwards(List.of(), null, "");
        }
    }

    /** One rendered section of a season post; also used as an embed field. */
    public record SeasonSection(String label, String value) {
        public SeasonSection {
            label = label == null ? "" : label;
            value = value == null ? "" : value;
        }
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
                recentPlayers.add(new PlayerDeaths(
                        player.uuid(),
                        player.name(),
                        player.optOutPublic(),
                        player.hideCoordinates(),
                        player.hidePvpOpponent(),
                        player.discordOptOut(),
                        recentDeaths));
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

    /**
     * Renders a manual season digest with the default (English) season labels.
     * See the overload taking {@link SeasonLabels} for lang-driven output.
     */
    public static String seasonContent(List<PlayerDeaths> players, SeasonRange range, ZoneId zone,
                                       DigestLabels labels, SeasonAwards awards) {
        return seasonContent(players, range, zone, labels, awards, SeasonLabels.english());
    }

    /** Renders the five season sections as the newline-separated Discord content. */
    public static String seasonContent(List<PlayerDeaths> players, SeasonRange range, ZoneId zone,
                                       DigestLabels labels, SeasonAwards awards, SeasonLabels seasonLabels) {
        return renderSections(seasonSections(players, range, zone, labels, awards, seasonLabels));
    }

    /** Section list for the season post; the same sections feed the Discord embed fields. */
    public static List<SeasonSection> seasonSections(List<PlayerDeaths> players, SeasonRange range, ZoneId zone,
                                                     DigestLabels labels, SeasonAwards awards) {
        return seasonSections(players, range, zone, labels, awards, SeasonLabels.english());
    }

    /**
     * Builds the season sections over an arbitrary inclusive period (§5).
     *
     * <p>DESIGN §8: totals and cause counts include opted-out deaths anonymously
     * ({@code causeCounts(..., true)}); named sections (most deaths, last words
     * and awards) use public players only, and hidden names are additionally
     * scrubbed from free text so an opted-out name cannot leak through last
     * words. An empty period returns an empty section list; version code sends
     * the lang-keyed "no deaths" message and posts nothing.
     */
    public static List<SeasonSection> seasonSections(List<PlayerDeaths> players, SeasonRange range, ZoneId zone,
                                                     DigestLabels labels, SeasonAwards awards,
                                                     SeasonLabels seasonLabels) {
        if (players == null || players.isEmpty() || range == null) {
            return List.of();
        }
        ZoneId effectiveZone = zone != null ? zone : ZoneOffset.UTC;
        DigestLabels effectiveLabels = labels != null ? labels : labelsFor(null);
        SeasonLabels season = seasonLabels != null ? seasonLabels : SeasonLabels.english();
        SeasonAwards effectiveAwards = awards != null ? awards : SeasonAwards.none();

        List<PlayerDeaths> inRange = CensusStats.filterRange(
                players, range.fromMillisInclusive(), range.toMillisInclusive());
        int total = CensusStats.totalDeaths(inRange);
        if (total == 0) {
            return List.of();
        }

        Set<String> hiddenNames = new HashSet<>();
        Set<String> publicNames = new HashSet<>();
        for (PlayerDeaths player : players) {
            if (player == null || player.name().isBlank()) {
                continue;
            }
            if (player.optOutPublic()) {
                hiddenNames.add(player.name());
            } else if (!player.discordOptOut()) {
                // §7 explicit: last-words/award names exclude discordOptOut too,
                // while digest totals and the "most deaths" ranking do not.
                publicNames.add(player.name());
            }
        }

        List<SeasonSection> sections = new ArrayList<>();
        String fromDate = TimeFormat.formatDate(range.fromMillisInclusive(), effectiveZone);
        String toDate = TimeFormat.formatDate(range.toMillisInclusive(), effectiveZone);
        sections.add(new SeasonSection(season.headerField(),
                format(season.header(), total, fromDate, toDate)));

        String topCauses = CensusStats.causeCounts(inRange, true).stream()
                .limit(3)
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .collect(Collectors.joining(", "));
        sections.add(new SeasonSection(effectiveLabels.topCauses(), topCauses));

        String mostDeaths = CensusStats.leaderboardDeaths(inRange, false).stream()
                .limit(3)
                .map(summary -> summary.name() + " (" + summary.deaths() + ")")
                .collect(Collectors.joining(", "));
        if (!mostDeaths.isEmpty()) {
            sections.add(new SeasonSection(effectiveLabels.mostDeaths(), scrub(mostDeaths, hiddenNames)));
        }

        List<SeasonLastWords> lastWords = new ArrayList<>();
        for (PlayerDeaths player : inRange) {
            if (player == null || player.optOutPublic() || player.discordOptOut()) {
                continue;
            }
            for (DeathEntry death : player.deaths()) {
                if (death == null || death.lastWords().isBlank()) {
                    continue;
                }
                lastWords.add(new SeasonLastWords(death.playerName(), death));
            }
        }
        lastWords.sort(null);
        String lastWordsValue = lastWords.stream()
                .limit(3)
                .map(Object::toString)
                .collect(Collectors.joining("; "));
        if (!lastWordsValue.isEmpty()) {
            sections.add(new SeasonSection(season.lastWords(), scrub(lastWordsValue, hiddenNames)));
        }

        List<String> awardLines = new ArrayList<>();
        List<String> completers = effectiveAwards.allCategoryCompleters().stream()
                .filter(publicNames::contains)
                .distinct()
                .toList();
        awardLines.add(format(season.allCategories(),
                completers.isEmpty() ? season.none() : String.join(", ", completers),
                completers.size()));
        DeathEntry longest = effectiveAwards.longestSurvival();
        String longestValue = longest != null && publicNames.contains(longest.playerName())
                ? longest.playerName() + " (" + TimeFormat.formatDuration(longest.survivalTicks()) + ")"
                : season.none();
        awardLines.add(format(season.longestSurvival(), longestValue));
        String rarest = effectiveAwards.rarestCause();
        awardLines.add(format(season.rarestCause(), rarest.isEmpty() ? season.none() : rarest));
        sections.add(new SeasonSection(season.awards(),
                scrub(String.join("; ", awardLines), hiddenNames)));

        return List.copyOf(sections);
    }

    /** Sort key for "most-respected last words": F count desc, then epoch desc. */
    private record SeasonLastWords(String name, DeathEntry death) implements Comparable<SeasonLastWords> {
        @Override
        public int compareTo(SeasonLastWords other) {
            int byF = Integer.compare(other.death.fCount(), death.fCount());
            return byF != 0 ? byF : Long.compare(other.death.epochMillis(), death.epochMillis());
        }

        @Override
        public String toString() {
            return name + ": \"" + death.lastWords() + "\""
                    + (death.fCount() > 0 ? " (F x" + death.fCount() + ")" : "");
        }
    }

    private static String renderSections(List<SeasonSection> sections) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sections.size(); i++) {
            SeasonSection section = sections.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            // The first section is the complete header line; its label is only
            // used as the Discord embed field name.
            if (i == 0 || section.label().isBlank()) {
                sb.append(section.value());
            } else {
                sb.append(section.label()).append(": ").append(section.value());
            }
        }
        return sb.toString();
    }

    private static String format(String template, Object... args) {
        try {
            return String.format(Locale.ROOT, template, args);
        } catch (IllegalFormatException e) {
            return template == null ? "" : template;
        }
    }

    /** Word-boundary, case-insensitive removal of opted-out names from free text (§5 risks). */
    private static String scrub(String text, Set<String> hiddenNames) {
        if (text == null || text.isEmpty() || hiddenNames.isEmpty()) {
            return text == null ? "" : text;
        }
        String result = text;
        for (String name : hiddenNames) {
            if (name.length() < 2) {
                continue;
            }
            result = result.replaceAll("(?i)\\b" + Pattern.quote(name) + "\\b", "(hidden)");
        }
        return result;
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
