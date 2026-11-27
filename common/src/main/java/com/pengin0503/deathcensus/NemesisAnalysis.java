package com.pengin0503.deathcensus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure nemesis tables for DESIGN 1.4.0 §4: the entity/cause/player that killed a
 * player most often. Everything is derived from retained {@link DeathEntry}
 * records; no kill counts are stored, the death-time hot path is untouched and
 * records with hidden coordinates still count (no positional output exists).
 *
 * <p>Three disjoint tables are supported: {@link Mode#MOB_SPECIES} groups by
 * {@code killerType} for non-player causes, {@link Mode#NAMED_RIVAL} groups by
 * killer type + custom name for non-player causes, and {@link Mode#PVP_RIVAL}
 * groups by {@code killerName} for player causes. {@code minecraft:player} and
 * the {@code player} cause category can never enter a mob table.
 *
 * <p>The full projection composes the same privacy rules as every other public
 * surface: opted-out victims are removed by the caller, opted-out rival names
 * are replaced by {@link #ANONYMOUS} via {@link HiddenIdentities} (word-boundary,
 * longest-first), and a rival hidden by the victim's {@code hidePvpOpponent}
 * flag is scrubbed through {@link DeathRedactor#scrubOpponent}. Self and admin
 * viewers receive the truthful names.
 */
public final class NemesisAnalysis {
    /** The three §4 tables, in presentation order. */
    public enum Mode {
        MOB_SPECIES("mob"),
        NAMED_RIVAL("named"),
        PVP_RIVAL("pvp");

        private final String token;

        Mode(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }

        public static Mode fromToken(String value) {
            if (value == null) {
                return null;
            }
            for (Mode mode : values()) {
                if (mode.token.equalsIgnoreCase(value) || mode.name().equalsIgnoreCase(value)) {
                    return mode;
                }
            }
            return null;
        }
    }

    /** Shared stand-in for a rival who must not be named on a public surface. */
    public static final String ANONYMOUS = HiddenIdentities.HIDDEN_MARKER;

    /** Canonical player killer type; never a mob species. */
    public static final String PLAYER_TYPE = "minecraft:player";

    private NemesisAnalysis() {
    }

    /** Ranks one table with a threshold of one; suitable for self/admin callers. */
    public static List<NemesisRow> rank(Collection<PlayerDeaths> players, Mode mode) {
        return rank(players, mode, 1);
    }

    /** Ranks one table; counts below {@code minCount} are omitted. */
    public static List<NemesisRow> rank(Collection<PlayerDeaths> players, Mode mode, int minCount) {
        return rank(players, mode, minCount, players, Map.of(), Map.of(), false);
    }

    /**
     * Ranks one table for a specific view. {@code source} contains the deaths to
     * tally (already period/victim filtered); {@code fullSnapshot} supplies the
     * hidden-identity dictionary so a name hidden elsewhere cannot leak through
     * this table. {@code privileged} (self/admin) keeps the truthful names.
     */
    public static List<NemesisRow> rank(Collection<PlayerDeaths> source, Mode mode, int minCount,
                                        Collection<PlayerDeaths> fullSnapshot,
                                        Map<String, String> displayNames,
                                        Map<String, PlayerPrivacy> privacyById,
                                        boolean privileged) {
        Mode table = mode == null ? Mode.MOB_SPECIES : mode;
        int threshold = Math.max(1, minCount);
        Map<String, String> names = displayNames == null ? Map.of() : displayNames;
        Map<String, PlayerPrivacy> privacy = privacyById == null ? Map.of() : privacyById;
        HiddenIdentities hidden = privileged
                ? HiddenIdentities.empty()
                : HiddenIdentities.of(fullSnapshot == null ? source : fullSnapshot);
        Set<String> knownPlayers = privileged ? Set.of() : knownPlayerNames(fullSnapshot == null ? source : fullSnapshot, names);

        Map<String, NemesisRow> counts = new LinkedHashMap<>();
        if (source == null) {
            return List.of();
        }
        for (PlayerDeaths victim : source) {
            if (victim == null) {
                continue;
            }
            for (DeathEntry death : victim.deaths()) {
                if (death == null || !matches(death, table)) {
                    continue;
                }
                String type = death.killerType();
                String rival = death.killerName();
                String key = groupingKey(table, type, rival);
                if (key.isEmpty()) {
                    continue;
                }
                String name = displayName(table, type, rival);
                if (!privileged) {
                    String scrubbed = hidden.scrub(name);
                    if (victim.hidePvpOpponent() && knownPlayers.contains(rival.toLowerCase(Locale.ROOT))) {
                        // Reuse the exact word-boundary helper hidePvpOpponent uses
                        // on death messages; an empty remainder means a full hit.
                        if (DeathRedactor.scrubOpponent(name, name).isEmpty()) {
                            scrubbed = ANONYMOUS;
                        }
                    }
                    if (!scrubbed.equals(name)) {
                        // All hidden rivals collapse into one anonymous row so the
                        // count stays truthful without naming anybody.
                        key = "hidden:" + table.token();
                        name = ANONYMOUS;
                    }
                }
                NemesisRow previous = counts.get(key);
                counts.put(key, new NemesisRow(table, key, type, name,
                        previous == null ? 1 : previous.count() + 1));
            }
        }
        List<NemesisRow> rows = new ArrayList<>();
        for (NemesisRow row : counts.values()) {
            if (row.count() >= threshold) {
                rows.add(row);
            }
        }
        rows.sort(Comparator.comparingInt(NemesisRow::count).reversed()
                .thenComparing(NemesisRow::key));
        return List.copyOf(rows);
    }

    /** Convenience overload for callers that only need the current-name map. */
    public static List<NemesisRow> rank(Collection<PlayerDeaths> source, Mode mode, int minCount,
                                        Collection<PlayerDeaths> fullSnapshot,
                                        Map<String, String> displayNames,
                                        Map<String, PlayerPrivacy> privacyById) {
        return rank(source, mode, minCount, fullSnapshot, displayNames, privacyById, false);
    }

    private static boolean matches(DeathEntry death, Mode mode) {
        boolean playerCause = "player".equalsIgnoreCase(death.causeCategory());
        boolean playerKiller = PLAYER_TYPE.equalsIgnoreCase(death.killerType());
        return switch (mode) {
            case MOB_SPECIES -> !playerCause && !playerKiller && !death.killerType().isBlank();
            case NAMED_RIVAL -> !playerCause && !playerKiller && !death.killerName().isBlank();
            case PVP_RIVAL -> playerCause && !death.killerName().isBlank();
        };
    }

    private static String groupingKey(Mode mode, String type, String rival) {
        return switch (mode) {
            case MOB_SPECIES -> type;
            case NAMED_RIVAL -> type + '\u0000' + rival;
            case PVP_RIVAL -> rival;
        };
    }

    private static String displayName(Mode mode, String type, String rival) {
        return switch (mode) {
            case MOB_SPECIES -> type;
            case NAMED_RIVAL, PVP_RIVAL -> rival;
        };
    }

    private static Set<String> knownPlayerNames(Collection<PlayerDeaths> snapshot, Map<String, String> names) {
        Set<String> result = new LinkedHashSet<>();
        for (String name : names.values()) {
            if (name != null && !name.isBlank()) {
                result.add(name.toLowerCase(Locale.ROOT));
            }
        }
        if (snapshot != null) {
            for (PlayerDeaths player : snapshot) {
                if (player == null) {
                    continue;
                }
                if (!player.name().isBlank()) {
                    result.add(player.name().toLowerCase(Locale.ROOT));
                }
                for (DeathEntry death : player.deaths()) {
                    if (death != null && !death.playerName().isBlank()) {
                        result.add(death.playerName().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return result;
    }
}
