package com.pengin0503.deathcensus.ui;

import java.util.List;

/** Public projection of one DeathEntry; adapters never receive the raw record. */
public record DeathDetailView(
        String targetUuid,
        long epochMillis,
        String displayName,
        String dateTime,
        String dimension,
        int x,
        int y,
        int z,
        String causeCategory,
        String deathMessage,
        String lastWords,
        String excuse,
        int fCount,
        long survivalTicks,
        int experienceLevel,
        String killerName,
        boolean canEditExcuse,
        boolean canDelete,
        boolean coordsHidden,
        List<String> witnesses
) {
    /** Compatibility constructor for callers that have no redaction marker or witnesses yet. */
    public DeathDetailView(
            String targetUuid, long epochMillis, String displayName, String dateTime,
            String dimension, int x, int y, int z, String causeCategory, String deathMessage,
            String lastWords, String excuse, int fCount, long survivalTicks, int experienceLevel,
            String killerName, boolean canEditExcuse, boolean canDelete
    ) {
        this(targetUuid, epochMillis, displayName, dateTime, dimension, x, y, z,
                causeCategory, deathMessage, lastWords, excuse, fCount, survivalTicks,
                experienceLevel, killerName, canEditExcuse, canDelete, false, List.of());
    }

    /** Constructor with witnesses but no coordinate-hidden marker. */
    public DeathDetailView(
            String targetUuid, long epochMillis, String displayName, String dateTime,
            String dimension, int x, int y, int z, String causeCategory, String deathMessage,
            String lastWords, String excuse, int fCount, long survivalTicks, int experienceLevel,
            String killerName, boolean canEditExcuse, boolean canDelete, List<String> witnesses
    ) {
        this(targetUuid, epochMillis, displayName, dateTime, dimension, x, y, z,
                causeCategory, deathMessage, lastWords, excuse, fCount, survivalTicks,
                experienceLevel, killerName, canEditExcuse, canDelete, false, witnesses);
    }

    public DeathDetailView {
        targetUuid = targetUuid == null ? "" : targetUuid;
        displayName = displayName == null ? "" : displayName;
        dateTime = dateTime == null ? "" : dateTime;
        dimension = dimension == null ? "" : dimension;
        causeCategory = causeCategory == null ? "" : causeCategory;
        deathMessage = deathMessage == null ? "" : deathMessage;
        lastWords = lastWords == null ? "" : lastWords;
        excuse = excuse == null ? "" : excuse;
        killerName = killerName == null ? "" : killerName;
        witnesses = witnesses == null ? List.of() : List.copyOf(witnesses);
    }
}
