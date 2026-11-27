package com.pengin0503.deathcensus.ui;

import com.pengin0503.deathcensus.DeathContext;

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
        List<String> witnesses,
        DeathContext context
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
                experienceLevel, killerName, canEditExcuse, canDelete, false, List.of(),
                DeathContext.EMPTY);
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
                experienceLevel, killerName, canEditExcuse, canDelete, false, witnesses,
                DeathContext.EMPTY);
    }

    /** Constructor with the PR-2 redaction marker and witnesses, no context yet. */
    public DeathDetailView(
            String targetUuid, long epochMillis, String displayName, String dateTime,
            String dimension, int x, int y, int z, String causeCategory, String deathMessage,
            String lastWords, String excuse, int fCount, long survivalTicks, int experienceLevel,
            String killerName, boolean canEditExcuse, boolean canDelete, boolean coordsHidden,
            List<String> witnesses
    ) {
        this(targetUuid, epochMillis, displayName, dateTime, dimension, x, y, z,
                causeCategory, deathMessage, lastWords, excuse, fCount, survivalTicks,
                experienceLevel, killerName, canEditExcuse, canDelete, coordsHidden, witnesses,
                DeathContext.EMPTY);
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
        context = context == null ? DeathContext.EMPTY : context;
    }
}
