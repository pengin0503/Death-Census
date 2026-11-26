package com.pengin0503.deathcensus.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record HistoryView(
        List<HistoryRow> rows,
        int page,
        int pageCount,
        int total,
        HistoryFilter filter,
        String sessionToken
) {
    public HistoryView {
        rows = rows == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(rows));
        filter = filter == null ? HistoryFilter.DEFAULT : filter;
        sessionToken = sessionToken == null ? "" : sessionToken;
    }
}
