package com.pengin0503.deathcensus.ui;

import com.pengin0503.deathcensus.ChronicleEvent;
import com.pengin0503.deathcensus.PlayerDeaths;
import com.pengin0503.deathcensus.PlayerPrivacy;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * DESIGN §7: another player's privacy flags require the PRIVACY_OTHER gate.
     * {@link #isAdmin()} is the LOOKUP node; version contexts override this to
     * resolve PRIVACY_OTHER through the same PermissionBridge seam.
     */
    default boolean canManagePrivacy(UUID targetUuid) {
        return UiPrivacy.canManagePrivacy(viewer(), targetUuid, isSelf(targetUuid) || isAdmin());
    }

    /** 1.4.0 §2: the awards page is hidden when the server disables awards. */
    default boolean awardsEnabled() {
        return true;
    }

    /** 1.4.0 §2: minimum count before an award row is listed (S4). */
    default int awardsMinCount() {
        return 1;
    }

    /** Lifetime category completion (1.4.0 §2 CATEGORY_COMPLETE), keyed by UUID. */
    default Map<String, Set<String>> lifetimeCategories() {
        return Map.of();
    }

    /** 1.4.0 §3: the chronicle page is read-only and hidden when disabled. */
    default boolean chronicleEnabled() {
        return true;
    }

    /** Stored chronicle rows; adapters read them from the version storage. */
    default List<ChronicleEvent> chronicleEvents() {
        return List.of();
    }

    /** 1.4.0 §4: the read-only Nemesis ranking page hides when disabled. */
    default boolean nemesisEnabled() {
        return true;
    }

    /** Minimum kill count before a mob-species row is listed on the Nemesis page. */
    default int nemesisMinMobCount() {
        return 3;
    }

    /** Minimum kill count before a named-mob/PvP row is listed on the Nemesis page. */
    default int nemesisMinNamedCount() {
        return 2;
    }

    /** 1.4.0 §5: the read-only Hotspots ranking page hides when disabled. */
    default boolean hotspotsEnabled() {
        return true;
    }

    /** Minimum deaths in a chunk before the Hotspots page lists it. */
    default int hotspotMinDeaths() {
        return 3;
    }

    /** Chunk edge length used by the Hotspots page (8–32 blocks). */
    default int hotspotChunkSize() {
        return 16;
    }

    /** 1.4.0 §6: the two-column Compare view hides when disabled. */
    default boolean compareEnabled() {
        return true;
    }

    /** 1.4.0 §7: the two read-only Distribution tabs hide when disabled. */
    default boolean distributionsEnabled() {
        return true;
    }

    /** Time bin edge length for the Distribution page (1/2/3/4/6/12 hours). */
    default int distributionTimeBinHours() {
        return 2;
    }

    /** Altitude band edge length for the Distribution page (8–64 blocks). */
    default int distributionYBandBlocks() {
        return 16;
    }

    /** 1.4.0 §8: the read-only Loops ranking page hides when disabled. */
    default boolean loopsEnabled() {
        return true;
    }

    /** Minimum consecutive nearby deaths before the Loops page lists a run. */
    default int deathLoopMinDeaths() {
        return 3;
    }

    /** Maximum gap in minutes between consecutive deaths in a loop. */
    default int deathLoopWindowMinutes() {
        return 10;
    }

    /** Maximum distance in blocks from the first death of a loop. */
    default int deathLoopRadiusBlocks() {
        return 48;
    }

    /** 1.4.0 §10: the read-only near-death ranking page hides when disabled. */
    default boolean nearDeathEnabled() {
        return true;
    }

    /** Resolves a bundled lang key for a language-neutral view row. */
    default String label(String key) {
        return key;
    }

    /** Live four-flag snapshot; adapters may override with a direct storage read. */
    default PlayerPrivacy getPrivacyFlags(UUID uuid) {
        if (uuid == null) {
            return PlayerPrivacy.none();
        }
        for (PlayerDeaths player : snapshot()) {
            if (player != null && player.uuid().equalsIgnoreCase(uuid.toString())) {
                return PlayerPrivacy.of(player);
            }
        }
        return PlayerPrivacy.none();
    }

    /** Server-side mutation of one flag; never trusts a client-supplied identity. */
    void setPrivacyFlag(UUID uuid, PlayerPrivacy.Flag flag, boolean value);

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
