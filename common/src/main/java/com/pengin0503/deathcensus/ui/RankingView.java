package com.pengin0503.deathcensus.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ranking page. {@code rows} contains formatted, public-only entries;
 * {@code anonymousTotalsLine} aggregates opted-out deaths without names.
 */
public record RankingView(
        String type,
        String period,
        int page,
        int pageCount,
        List<String> rows,
        String anonymousTotalsLine
) {
    public RankingView {
        type = type == null ? "" : type;
        period = period == null ? "" : period;
        rows = rows == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(rows));
        anonymousTotalsLine = anonymousTotalsLine == null ? "" : anonymousTotalsLine;
    }
}
