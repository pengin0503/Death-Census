package com.pengin0503.deathcensus;

/**
 * Immutable outcome of DESIGN 1.4.0 §6 player comparison. Every displayed value
 * is already privacy-projected by {@link CompareAnalysis}; no raw opponent name
 * or coordinate is carried into this record.
 *
 * <p>There is deliberately no wins/losses component: the mod only records
 * deaths, so a win count would be fabricated (§15 Q15).
 */
public record CompareResult(
        Failure failure,
        String requestedNameA,
        String requestedNameB,
        Row a,
        Row b
) {
    /** Failure reasons; both are rendered as an unknown-player message in public surfaces. */
    public enum Failure {
        NONE,
        UNKNOWN_PLAYER,
        HIDDEN_PLAYER
    }

    /**
     * One side of the comparison. {@code summary} provides totals and survival
     * extremes; {@code averageSurvivalTicks} is derived from the same deaths.
     * {@code playerCausedDeaths} is the count of {@code causeCategory=player}
     * deaths, never a win tally. {@code topOpponent} is already redacted and
     * {@code opponentHidden} tells renderers to use an anonymous label.
     */
    public record Row(
            String uuid,
            String name,
            CensusStats.PlayerSummary summary,
            long averageSurvivalTicks,
            String topCause,
            int playerCausedDeaths,
            String topOpponent,
            int topOpponentCount,
            boolean opponentHidden
    ) {
        public Row {
            uuid = uuid == null ? "" : uuid;
            name = name == null ? "" : name;
            topCause = topCause == null ? "" : topCause;
            topOpponent = topOpponent == null ? "" : topOpponent;
            summary = summary == null
                    ? new CensusStats.PlayerSummary(uuid, name, 0, 0, -1, 0L, 0L, "")
                    : summary;
        }

        /** Zero-death row; the player still resolves but has no period records. */
        public static Row empty(String uuid, String name) {
            String safeUuid = uuid == null ? "" : uuid;
            String safeName = name == null ? "" : name;
            return new Row(safeUuid, safeName,
                    new CensusStats.PlayerSummary(safeUuid, safeName, 0, 0, -1, 0L, 0L, ""),
                    0L, "", 0, "", 0, false);
        }
    }

    public CompareResult {
        requestedNameA = requestedNameA == null ? "" : requestedNameA;
        requestedNameB = requestedNameB == null ? "" : requestedNameB;
    }

    public boolean ok() {
        return failure == Failure.NONE && a != null && b != null;
    }

    public boolean hidden() {
        return failure == Failure.HIDDEN_PLAYER;
    }

    /** Alias for the first side, used by two-column renderers. */
    public Row left() {
        return a;
    }

    /** Alias for the second side, used by two-column renderers. */
    public Row right() {
        return b;
    }

    public static CompareResult success(Row a, Row b) {
        return new CompareResult(Failure.NONE, "", "", a, b);
    }

    public static CompareResult success(String requestedNameA, String requestedNameB, Row a, Row b) {
        return new CompareResult(Failure.NONE, requestedNameA, requestedNameB, a, b);
    }

    public static CompareResult unknown(String requestedNameA, String requestedNameB) {
        return new CompareResult(Failure.UNKNOWN_PLAYER, requestedNameA, requestedNameB, null, null);
    }

    public static CompareResult hidden(String requestedNameA, String requestedNameB) {
        return new CompareResult(Failure.HIDDEN_PLAYER, requestedNameA, requestedNameB, null, null);
    }
}
