package com.pengin0503.deathcensus.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Two-column Compare view reached from the Ranking screen. Both adapters render
 * the already localized rows side by side; the view itself carries no raw data
 * beyond the two display names. An empty row list means "no query yet" or
 * "unknown/hidden target", with the notice carried in {@code message}.
 */
public record CompareView(
        String period,
        String nameA,
        String nameB,
        List<String> rowsA,
        List<String> rowsB,
        String message,
        String sessionToken
) {
    public CompareView {
        period = period == null ? "" : period;
        nameA = nameA == null ? "" : nameA;
        nameB = nameB == null ? "" : nameB;
        rowsA = rowsA == null
                ? List.of() : Collections.unmodifiableList(new ArrayList<>(rowsA));
        rowsB = rowsB == null
                ? List.of() : Collections.unmodifiableList(new ArrayList<>(rowsB));
        message = message == null ? "" : message;
        sessionToken = sessionToken == null ? "" : sessionToken;
    }
}
