package com.pengin0503.deathcensus.ui;

import com.pengin0503.deathcensus.PlayerDeaths;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Narrow bridge implemented by each version adapter. It is bound to one
 * viewer; all permissions are re-evaluated here from live server state.
 */
public interface UiContext {
    UUID viewer();

    List<PlayerDeaths> snapshot();

    boolean isAdmin();

    boolean isSelf(UUID uuid);

    boolean isOptedOut(UUID uuid);

    /** DESIGN §6: self OR admin OR not opted out. */
    default boolean canViewDetail(UUID targetUuid) {
        return UiPrivacy.canViewDetail(viewer(),
                targetUuid == null ? null : targetUuid.toString(), isOptedOut(targetUuid), isAdmin());
    }

    /** Server-side mutation counter, bumped on record/delete/excuse/respect/optout changes. */
    int dataVersion();

    /** Play-time survival ticks, computed version-side from PLAY_TIME - lastDeathPlayTicks. */
    int currentSurvivalTicks(UUID uuid);

    ZoneId zoneId();

    default long nowMillis() {
        return System.currentTimeMillis();
    }

    UiSession currentSession();

    UiSession newSession(HistoryFilter filter, UiSession.RankingSelection ranking);

    /** Rotates the token on navigation; null presented session starts a fresh one. */
    UiSession touchSession(UiSession presented, HistoryFilter filter, UiSession.RankingSelection ranking);

    void invalidateSession();

    void sendNotice(UiText text);

    boolean setExcuse(UUID uuid, long epoch, String text);

    boolean payRespect(UUID sender, UUID victim, long epoch);

    /** Backup-first delete; returns false when the safety backup failed. */
    boolean deleteAfterBackup(UUID uuid, long epoch);

    void setOptOut(boolean optOut);
}
