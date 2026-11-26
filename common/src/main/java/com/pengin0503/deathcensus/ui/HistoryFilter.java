package com.pengin0503.deathcensus.ui;

import com.pengin0503.deathcensus.CauseClassifier;
import com.pengin0503.deathcensus.DeathEntry;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * Session-owned history inputs. Period cutoffs mirror
 * {@code CensusCommands.sinceMillis}; category/dimension matching mirrors its
 * {@code filterPredicate} (known category first, exact dimension id second).
 */
public record HistoryFilter(String period, String category, String dimension, String query) {
    public static final List<String> PERIODS = List.of("all", "today", "7d", "30d");
    public static final String ALL = "all";
    public static final int MAX_QUERY_LENGTH = 64;
    private static final long DAY_MILLIS = 86_400_000L;

    public static final HistoryFilter DEFAULT = new HistoryFilter(ALL, ALL, ALL, "");

    public HistoryFilter {
        period = normalizePeriod(period);
        category = normalizeChoice(category);
        dimension = dimension == null ? ALL : dimension.trim();
        if (dimension.isEmpty()) {
            dimension = ALL;
        }
        query = cleanQuery(query);
    }

    public static String normalizePeriod(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return PERIODS.contains(normalized) ? normalized : ALL;
    }

    private static String normalizeChoice(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? ALL : normalized;
    }

    public static String cleanQuery(String value) {
        String cleaned = value == null ? "" : value.trim();
        return cleaned.length() > MAX_QUERY_LENGTH ? cleaned.substring(0, MAX_QUERY_LENGTH) : cleaned;
    }

    /** Returns a copy with all inputs normalized (the canonical form). */
    public HistoryFilter normalize() {
        return new HistoryFilter(period, category, dimension, query);
    }

    public HistoryFilter withPeriod(String newPeriod) {
        return new HistoryFilter(newPeriod, category, dimension, query);
    }

    /** Cutoff for this period; non-positive means "no filtering", as in CensusStats.filterSince. */
    public long sinceMillis(long nowMillis, ZoneId zone) {
        ZoneId effectiveZone = zone == null ? ZoneId.systemDefault() : zone;
        return switch (period) {
            case "today" -> LocalDate.from(Instant.ofEpochMilli(nowMillis).atZone(effectiveZone))
                    .atStartOfDay(effectiveZone).toInstant().toEpochMilli();
            case "7d" -> nowMillis - 7L * DAY_MILLIS;
            case "30d" -> nowMillis - 30L * DAY_MILLIS;
            default -> 0L;
        };
    }

    public long sinceMillis(ZoneId zone) {
        return sinceMillis(System.currentTimeMillis(), zone);
    }

    public boolean matches(DeathEntry entry) {
        return matches(entry, System.currentTimeMillis(), ZoneId.systemDefault());
    }

    public boolean matches(DeathEntry entry, long nowMillis, ZoneId zone) {
        if (entry == null || entry.epochMillis() < sinceMillis(nowMillis, zone)) {
            return false;
        }
        if (!ALL.equals(category) && !category.equals(entry.causeCategory())) {
            return false;
        }
        if (!ALL.equals(dimension) && !dimension.equals(entry.dimension())) {
            return false;
        }
        if (!query.isEmpty()) {
            String needle = query.toLowerCase(Locale.ROOT);
            return containsIgnoreCase(entry.playerName(), needle) || containsIgnoreCase(entry.deathMessage(), needle);
        }
        return true;
    }

    public boolean isKnownCategoryOrDimension(java.util.Collection<String> knownDimensions) {
        return ALL.equals(category) || CauseClassifier.isKnownCategory(category)
                || (knownDimensions != null && knownDimensions.contains(category));
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }
}
