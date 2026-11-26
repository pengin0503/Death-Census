package com.pengin0503.deathcensus;

import java.util.List;
import java.util.Objects;

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
        int experienceLevel,
        String excuse,
        String lastWords,
        int fCount,
        List<WitnessRef> witnesses,
        DeathContext context
) {
    /**
     * Compatibility constructor for the 1.3.0 20-component shape. DESIGN 1.4.0
     * §13.2 keeps every existing call site working: old records have neither
     * witnesses nor death context, so both default to their empty forms.
     */
    public DeathEntry(
            String playerUuid, String playerName, long epochMillis, long gameDay,
            int playTicks, int survivalTicks, String dimension, double x, double y, double z,
            String causeCategory, String causeId, String deathMessage, String killerType,
            String killerName, String gameMode, int experienceLevel, String excuse,
            String lastWords, int fCount) {
        this(playerUuid, playerName, epochMillis, gameDay, playTicks, survivalTicks,
                dimension, x, y, z, causeCategory, causeId, deathMessage, killerType,
                killerName, gameMode, experienceLevel, excuse, lastWords, fCount,
                List.of(), DeathContext.EMPTY);
    }

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
        excuse = excuse == null ? "" : excuse;
        lastWords = lastWords == null ? "" : lastWords;
        fCount = Math.max(0, fCount);
        witnesses = WitnessRef.copyOf(witnesses);
        context = context == null ? DeathContext.EMPTY : context;
    }
}
