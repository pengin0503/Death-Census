package com.pengin0503.deathchronicle;

public record DeathEntry(
        String playerUuid,
        String playerName,
        long epochMillis,
        long gameDay,
        int playTicks,
        int survivalTicks,
        String dimension,
        double x,
        double y,
        double z,
        String causeCategory,
        String causeId,
        String deathMessage,
        String killerType,
        String killerName,
        String gameMode,
        int experienceLevel
) {
    public DeathEntry {
        playerUuid = playerUuid == null ? "" : playerUuid;
        playerName = playerName == null ? "" : playerName;
        playTicks = Math.max(0, playTicks);
        survivalTicks = Math.max(0, survivalTicks);
        dimension = dimension == null ? "" : dimension;
        causeCategory = causeCategory == null ? "" : causeCategory;
        causeId = causeId == null ? "" : causeId;
        deathMessage = deathMessage == null ? "" : deathMessage;
        killerType = killerType == null ? "" : killerType;
        killerName = killerName == null ? "" : killerName;
        gameMode = gameMode == null ? "" : gameMode;
        experienceLevel = Math.max(0, experienceLevel);
    }
}
