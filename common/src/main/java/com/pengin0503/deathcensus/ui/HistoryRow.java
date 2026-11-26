package com.pengin0503.deathcensus.ui;

/** One visible death in a history page; the display line is already bounded. */
public record HistoryRow(long epochMillis, String targetUuid, String displayName, String displayLine) {
    public HistoryRow {
        targetUuid = targetUuid == null ? "" : targetUuid;
        displayName = displayName == null ? "" : displayName;
        displayLine = displayLine == null ? "" : displayLine;
    }
}
