package com.pengin0503.deathcensus.ui;

/** Short-lived server-side session backing a viewer's UI navigation. */
public record UiSession(
        String token,
        long createdAtMillis,
        HistoryFilter filter,
        RankingSelection ranking,
        long dataVersion
) {
    /** DESIGN 1.2.0 §8 default: five minutes. */
    public static final long TTL_MILLIS = 5L * 60L * 1000L;

    public static final RankingSelection DEFAULT_RANKING = new RankingSelection("deaths", "all");

    public record RankingSelection(String type, String period) {
        public static final java.util.Set<String> TYPES = java.util.Set.of(
                "deaths", "survival", "fastest", "rarest", "awards", "chronicle", "nemesis", "hotspots",
                "distribution_time", "distribution_y", "loops", "neardeath");

        public RankingSelection {
            String normalizedType = type == null ? "" : type.trim().toLowerCase(java.util.Locale.ROOT);
            type = TYPES.contains(normalizedType) ? normalizedType : "deaths";
            period = HistoryFilter.normalizePeriod(period);
        }

        public static RankingSelection of(String type, String period) {
            return new RankingSelection(type, period);
        }
    }

    public UiSession {
        token = token == null ? "" : token;
        createdAtMillis = Math.max(0L, createdAtMillis);
        filter = filter == null ? HistoryFilter.DEFAULT : filter;
        ranking = ranking == null ? DEFAULT_RANKING : ranking;
    }

    public boolean isExpired(long nowMillis) {
        return nowMillis < createdAtMillis || nowMillis - createdAtMillis >= TTL_MILLIS;
    }

    public boolean isExpired() {
        return isExpired(System.currentTimeMillis());
    }

    public UiSession withDataVersion(long version) {
        return new UiSession(token, createdAtMillis, filter, ranking, version);
    }
}
