package com.pengin0503.deathcensus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure witness logic for DESIGN 1.4.0 §1: nearest-first bounded selection at
 * death time and the "died most often alongside" ranking over retained records.
 *
 * <p>No version APIs, no I/O and no Minecraft types live here. The version
 * {@code DeathTracker} only builds the small candidate tuple list and calls
 * {@link #nearest}; every surface calls {@link #rank} with a public-view
 * composed by {@link WitnessRedactor}.
 */
public final class WitnessAnalysis {
    /** One nearby player observed during the bounded death-time scan. */
    public record Candidate(String uuid, String name, double distanceSquared) {
        public Candidate {
            uuid = uuid == null ? "" : uuid;
            name = name == null ? "" : name;
            // Non-finite/negative distances sort last instead of corrupting the order.
            if (!Double.isFinite(distanceSquared) || distanceSquared < 0.0d) {
                distanceSquared = Double.MAX_VALUE;
            }
        }

        /** True for the no-op candidate; such entries never become witness refs. */
        public boolean isEmptyCandidate() {
            return uuid.isEmpty() && name.isEmpty();
        }
    }

    /** One row of the witness ranking: a named (or anonymized) witness and a count. */
    public record Row(String uuid, String name, int count) {
        public Row {
            uuid = uuid == null ? "" : uuid;
            name = name == null ? "" : name;
            count = Math.max(0, count);
        }
    }

    private WitnessAnalysis() {
    }

    /**
     * Picks the {@code cap} nearest distinct witnesses, nearest first. Ties are
     * broken by UUID then name so the result is deterministic and independent of
     * the online-player iteration order. {@code cap <= 0} disables storage and
     * returns the shared immutable empty list without copying anything.
     */
    public static List<WitnessRef> nearest(List<Candidate> candidates, int cap) {
        if (cap <= 0 || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<Candidate> sorted = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            if (candidate != null && !candidate.isEmptyCandidate() && !candidate.uuid().isEmpty()) {
                sorted.add(candidate);
            }
        }
        sorted.sort(Comparator.<Candidate>comparingDouble(Candidate::distanceSquared)
                .thenComparing(Candidate::uuid)
                .thenComparing(Candidate::name));
        Map<String, WitnessRef> distinct = new LinkedHashMap<>();
        for (Candidate candidate : sorted) {
            if (distinct.containsKey(candidate.uuid())) {
                continue;
            }
            distinct.put(candidate.uuid(), new WitnessRef(candidate.uuid(), candidate.name()));
            if (distinct.size() >= cap) {
                break;
            }
        }
        return List.copyOf(distinct.values());
    }

    /** Ranks witnesses across every retained death in the snapshot. */
    public static List<Row> rank(Collection<PlayerDeaths> players) {
        return rank(players, null, 0L, Map.of(), Map.of(), false);
    }

    /** Ranks witnesses across deaths at or after {@code sinceMillis} (0 = all). */
    public static List<Row> rank(Collection<PlayerDeaths> players, long sinceMillis) {
        return rank(players, null, sinceMillis, Map.of(), Map.of(), false);
    }

    /** Ranks the witnesses of one victim, identified by UUID, over a period. */
    public static List<Row> rankFor(Collection<PlayerDeaths> players, String victimUuid, long sinceMillis) {
        return rank(players, victimUuid, sinceMillis, Map.of(), Map.of(), false);
    }

    /**
     * Ranking used by every named public surface. {@code displayNames} and
     * {@code privacyById} are the current snapshot maps from
     * {@link WitnessRedactor}; an opted-out witness is merged into one anonymous
     * row for an ordinary viewer, while self/admin keep the captured identity.
     * The input snapshot is never mutated.
     */
    public static List<Row> rank(Collection<PlayerDeaths> players, String victimUuid, long sinceMillis,
                                 Map<String, String> displayNames, Map<String, PlayerPrivacy> privacyById,
                                 boolean privileged) {
        List<PlayerDeaths> nonNull = new ArrayList<>();
        if (players != null) {
            for (PlayerDeaths player : players) {
                if (player != null) {
                    nonNull.add(player);
                }
            }
        }
        Collection<PlayerDeaths> inPeriod = sinceMillis > 0L
                ? CensusStats.filterSince(nonNull, sinceMillis)
                : nonNull;
        if (inPeriod == null || inPeriod.isEmpty()) {
            return List.of();
        }
        Map<String, String> names = displayNames == null ? Map.of() : displayNames;
        Map<String, PlayerPrivacy> privacy = privacyById == null ? Map.of() : privacyById;
        Map<String, Row> counts = new LinkedHashMap<>();
        for (PlayerDeaths player : inPeriod) {
            if (player == null) {
                continue;
            }
            if (victimUuid != null && !victimUuid.isEmpty()
                    && !player.uuid().equalsIgnoreCase(victimUuid)) {
                continue;
            }
            for (DeathEntry death : player.deaths()) {
                if (death == null) {
                    continue;
                }
                for (WitnessRef ref : death.witnesses()) {
                    if (ref == null || ref.isEmpty()) {
                        continue;
                    }
                    PlayerPrivacy witnessPrivacy = privacy.get(ref.uuid());
                    boolean hidden = !privileged && witnessPrivacy != null && witnessPrivacy.hidesPublicIdentity();
                    String key = hidden ? "" : ref.uuid();
                    String name = hidden
                            ? WitnessRedactor.HIDDEN_NAME_MARKER
                            : resolveName(ref, names);
                    Row previous = counts.get(key);
                    counts.put(key, new Row(key, name, previous == null ? 1 : previous.count() + 1));
                }
            }
        }
        List<Row> rows = new ArrayList<>(counts.values());
        rows.sort(Comparator.<Row>comparingInt(Row::count).reversed()
                .thenComparing(Row::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Row::uuid));
        return List.copyOf(rows);
    }

    private static String resolveName(WitnessRef ref, Map<String, String> names) {
        String resolved = names.get(ref.uuid());
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }
        return ref.nameAtDeath().isEmpty() ? ref.uuid() : ref.nameAtDeath();
    }
}
