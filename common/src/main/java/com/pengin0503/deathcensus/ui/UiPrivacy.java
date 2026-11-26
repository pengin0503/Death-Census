package com.pengin0503.deathcensus.ui;

import com.pengin0503.deathcensus.CensusStats;
import com.pengin0503.deathcensus.DeathEntry;
import com.pengin0503.deathcensus.PlayerDeaths;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** Pure DESIGN §8 / 1.2.0 §6 visibility decisions over common data types. */
public final class UiPrivacy {
    private UiPrivacy() {
    }

    /** canViewDetail(viewer, target) = self OR admin OR !optOutPublic. */
    public static boolean canViewDetail(UUID viewer, String targetUuid, boolean targetOptOut, boolean admin) {
        if (viewer == null || targetUuid == null || targetUuid.isEmpty()) {
            return false;
        }
        return admin || viewer.toString().equalsIgnoreCase(targetUuid) || !targetOptOut;
    }

    public static boolean canViewDetail(UUID viewer, PlayerDeaths target, boolean admin) {
        return target != null && canViewDetail(viewer, target.uuid(), target.optOutPublic(), admin);
    }

    public static boolean canViewDetail(UUID viewer, DeathEntry entry, boolean targetOptOut, boolean admin) {
        return entry != null && canViewDetail(viewer, entry.playerUuid(), targetOptOut, admin);
    }

    public static boolean canViewDetail(
            UUID viewer, DeathEntry entry, Collection<PlayerDeaths> snapshot, boolean admin
    ) {
        if (entry == null) {
            return false;
        }
        boolean optedOut = optOutFor(snapshot, entry.playerUuid());
        return canViewDetail(viewer, entry.playerUuid(), optedOut, admin);
    }

    /** Excuses are self-only; delete is admin-only (DESIGN §3.2). */
    public static boolean canEditExcuse(UUID viewer, String targetUuid) {
        return viewer != null && viewer.toString().equalsIgnoreCase(targetUuid);
    }

    public static boolean canDelete(UUID viewer, String targetUuid, boolean admin) {
        return admin && viewer != null && targetUuid != null && !targetUuid.isEmpty();
    }

    /** DESIGN §7: self always, other only with the PRIVACY_OTHER decision. */
    public static boolean canManagePrivacy(UUID viewer, UUID targetUuid, boolean otherAllowed) {
        return viewer != null && targetUuid != null
                && (viewer.equals(targetUuid) || otherAllowed);
    }

    /** Resolves the four flags from a snapshot; absent players get the defaults. */
    public static com.pengin0503.deathcensus.PlayerPrivacy privacyFor(
            Collection<PlayerDeaths> players, String uuid
    ) {
        if (players == null || uuid == null) {
            return com.pengin0503.deathcensus.PlayerPrivacy.none();
        }
        for (PlayerDeaths player : players) {
            if (player != null && player.uuid().equalsIgnoreCase(uuid)) {
                return com.pengin0503.deathcensus.PlayerPrivacy.of(player);
            }
        }
        return com.pengin0503.deathcensus.PlayerPrivacy.none();
    }

    /** Players whose names/records may be shown; the viewer always sees themselves. */
    public static List<PlayerDeaths> visiblePlayers(
            Collection<PlayerDeaths> players, UUID viewer, boolean admin
    ) {
        List<PlayerDeaths> visible = new ArrayList<>();
        if (players == null) {
            return List.of();
        }
        for (PlayerDeaths player : players) {
            if (player != null && canViewDetail(viewer, player, admin)) {
                visible.add(player);
            }
        }
        return List.copyOf(visible);
    }

    public static List<DeathEntry> visibleDeaths(
            Collection<PlayerDeaths> players, UUID viewer, boolean admin
    ) {
        List<DeathEntry> visible = new ArrayList<>();
        for (PlayerDeaths player : visiblePlayers(players, viewer, admin)) {
            visible.addAll(player.deaths());
        }
        return List.copyOf(visible);
    }

    /** Latest death the viewer may show on Home; generic notice when none is viewable. */
    public static Optional<DeathEntry> lastVisibleDeath(
            Collection<PlayerDeaths> players, UUID viewer, boolean admin
    ) {
        return visibleDeaths(players, viewer, admin).stream()
                .max(Comparator.comparingLong(DeathEntry::epochMillis)
                        .thenComparing(DeathEntry::playerUuid));
    }

    public static String displayName(Collection<PlayerDeaths> players, String uuid) {
        if (players == null || uuid == null) {
            return "";
        }
        for (PlayerDeaths player : players) {
            if (player != null && player.uuid().equalsIgnoreCase(uuid)) {
                return player.name();
            }
        }
        return "";
    }

    public static boolean optOutFor(Collection<PlayerDeaths> players, String uuid) {
        if (players == null || uuid == null) {
            return false;
        }
        for (PlayerDeaths player : players) {
            if (player != null && player.uuid().equalsIgnoreCase(uuid)) {
                return player.optOutPublic();
            }
        }
        return false;
    }

    // Anonymous aggregates deliberately reuse CensusStats with includeOptOut=true.
    // No name from these players may ever be attached to the resulting numbers.

    public static int anonymousServerTotal(Collection<PlayerDeaths> players) {
        return CensusStats.totalDeaths(players);
    }

    public static List<Map.Entry<String, Integer>> anonymousCauseTotals(Collection<PlayerDeaths> players) {
        return CensusStats.causeCounts(players, true);
    }

    public static String anonymousTotalsLine(Collection<PlayerDeaths> players) {
        return "total=" + anonymousServerTotal(players) + anonymousCauseTotals(players).stream()
                .map(entry -> " · " + entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining());
    }

    public static List<CensusStats.PlayerSummary> publicDeathRankings(Collection<PlayerDeaths> players) {
        return CensusStats.leaderboardDeaths(players, false);
    }
}
