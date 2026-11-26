package com.pengin0503.deathcensus.ui;

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
        boolean canDelete
) {
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
    }
}
