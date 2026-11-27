package com.pengin0503.deathcensus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure near-death logic for DESIGN 1.4.0 §10: bounded appends, dedupe keys,
 * per-player counts/kind splits and privacy-aware ranking.
 *
 * <p>Nothing here contributes to {@link CensusStats}: near deaths are a separate
 * counter and never become {@link DeathEntry} records, death totals, cause
 * percentages, leaderboards or milestones. No version APIs or I/O live here.
 */
public final class NearDeathAnalysis {
    /** §13 defaults: 50 saves per player, 500 server-wide. */
    public static final int DEFAULT_MAX_PER_PLAYER = 50;
    public static final int SERVER_CAP = 500;
    public static final long DEFAULT_DEDUP_WINDOW_MILLIS = 2000L;
    public static final long MIN_DEDUP_WINDOW_MILLIS = 500L;
    public static final long MAX_DEDUP_WINDOW_MILLIS = 10_000L;

    /** One player's aggregate: both kinds stay visible but never join death totals. */
    public record Row(String uuid, String name, int total, int totem, int cancelled, long lastEpochMillis) {
        public Row {
            uuid = uuid == null ? "" : uuid;
            name = name == null ? "" : name;
            total = Math.max(0, total);
            totem = Math.max(0, totem);
            cancelled = Math.max(0, cancelled);
        }
    }

    /** Anonymous kind split of any snapshot, kept separate from death aggregates. */
    public record Split(int total, int totem, int cancelled) {
        public static final Split EMPTY = new Split(0, 0, 0);

        public Split {
            total = Math.max(0, total);
            totem = Math.max(0, totem);
            cancelled = Math.max(0, cancelled);
        }
    }

    /** Result of a bounded append; only the oldest events are dropped. */
    public record CapResult(List<NearDeathEvent> events, int dropped) {
        public CapResult {
            events = NearDeathEvent.copyOf(events);
            dropped = Math.max(0, dropped);
        }
    }

    private NearDeathAnalysis() {
    }

    /** Clamps the configured dedupe window to §10's 500–10,000 ms range. */
    public static long dedupWindow(long millis) {
        if (millis < MIN_DEDUP_WINDOW_MILLIS) {
            return MIN_DEDUP_WINDOW_MILLIS;
        }
        return Math.min(MAX_DEDUP_WINDOW_MILLIS, millis);
    }

    /**
     * §10 dedupe key: {@code (uuid, epochMillis / window, causeId)}. One save
     * observed by both hooks therefore counts once. The window is clamped so a
     * malformed config cannot change the key space mid-session.
     */
    public static String dedupeKey(String uuid, long epochMillis, String causeId, long windowMillis) {
        long window = dedupWindow(windowMillis <= 0L ? DEFAULT_DEDUP_WINDOW_MILLIS : windowMillis);
        long bucket = epochMillis < 0L ? -1L : epochMillis / window;
        String id = causeId == null ? "" : causeId.trim().toLowerCase(Locale.ROOT);
        return (uuid == null ? "" : uuid) + "|" + bucket + "|" + id;
    }

    public static String dedupeKey(NearDeathEvent event, long windowMillis) {
        return event == null ? "" : dedupeKey(event.uuid(), event.epochMillis(), event.causeId(), windowMillis);
    }

    /**
     * True when an equivalent event is already in the bounded tail of a player's
     * stored history. Only the last 16 entries are scanned, keeping capture O(1).
     */
    public static boolean isDuplicate(List<NearDeathEvent> existing, NearDeathEvent candidate, long windowMillis) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        List<NearDeathEvent> events = NearDeathEvent.copyOf(existing);
        String key = dedupeKey(candidate, windowMillis);
        int from = Math.max(0, events.size() - 16);
        for (int i = events.size() - 1; i >= from; i--) {
            NearDeathEvent prior = events.get(i);
            if (prior != null && key.equals(dedupeKey(prior, windowMillis))) {
                return true;
            }
        }
        return false;
    }

    /** Appends events in order, dropping the oldest beyond {@code perPlayerCap}. */
    public static CapResult appendBounded(List<NearDeathEvent> existing, Collection<NearDeathEvent> additions,
                                          int perPlayerCap) {
        List<NearDeathEvent> out = new ArrayList<>();
        for (NearDeathEvent event : NearDeathEvent.copyOf(existing)) {
            if (event != null && !event.isEmpty()) {
                out.add(event);
            }
        }
        int before = out.size();
        if (additions != null) {
            for (NearDeathEvent event : additions) {
                if (event != null && !event.isEmpty()) {
                    out.add(event);
                }
            }
        }
        int cap = Math.max(0, perPlayerCap);
        int excess = Math.max(0, out.size() - cap);
        if (excess > 0) {
            out.subList(0, excess).clear();
        }
        return new CapResult(List.copyOf(out), excess);
    }

    /** Total number of stored near-death saves; never the same as a death total. */
    public static int count(Collection<PlayerDeaths> players) {
        return split(players).total();
    }

    public static int countFor(PlayerDeaths player) {
        return player == null ? 0 : NearDeathEvent.copyOf(player.nearDeaths()).size();
    }

    /** Kind split over a snapshot; nulls and the no-op sentinel are ignored. */
    public static Split split(Collection<PlayerDeaths> players) {
        int totem = 0;
        int cancelled = 0;
        if (players != null) {
            for (PlayerDeaths player : players) {
                if (player == null) {
                    continue;
                }
                for (NearDeathEvent event : player.nearDeaths()) {
                    if (event == null || event.isEmpty()) {
                        continue;
                    }
                    if (event.kind() == NearDeathEvent.Kind.TOTEM) {
                        totem++;
                    } else {
                        cancelled++;
                    }
                }
            }
        }
        return new Split(totem + cancelled, totem, cancelled);
    }

    /** Per-player ranking, count descending then name/UUID; period-filtered. */
    public static List<Row> rank(Collection<PlayerDeaths> players, long sinceMillis) {
        return rank(players, sinceMillis, Map.of(), Map.of(), false);
    }

    /**
     * Public/privileged ranking. Opted-out players' names are hidden from an
     * ordinary viewer but their anonymous counts remain; self/admin keep the
     * captured name. The input snapshot is never mutated.
     */
    public static List<Row> rank(Collection<PlayerDeaths> players, long sinceMillis,
                                 Map<String, String> displayNames, Map<String, PlayerPrivacy> privacyById,
                                 boolean privileged) {
        Map<String, String> names = displayNames == null ? Map.of() : displayNames;
        Map<String, PlayerPrivacy> privacy = privacyById == null ? Map.of() : privacyById;
        Map<String, Row> counts = new LinkedHashMap<>();
        if (players == null) {
            return List.of();
        }
        for (PlayerDeaths player : players) {
            if (player == null) {
                continue;
            }
            int totem = 0;
            int cancelled = 0;
            long last = 0L;
            for (NearDeathEvent event : player.nearDeaths()) {
                if (event == null || event.isEmpty() || (sinceMillis > 0L && event.epochMillis() < sinceMillis)) {
                    continue;
                }
                if (event.kind() == NearDeathEvent.Kind.TOTEM) {
                    totem++;
                } else {
                    cancelled++;
                }
                last = Math.max(last, event.epochMillis());
            }
            int total = totem + cancelled;
            if (total <= 0) {
                continue;
            }
            PlayerPrivacy flags = privacy.get(player.uuid());
            boolean hidden = !privileged && flags != null && flags.hidesPublicIdentity();
            String name = hidden
                    ? WitnessRedactor.HIDDEN_NAME_MARKER
                    : resolveName(player, names);
            counts.put(player.uuid(), new Row(player.uuid(), name, total, totem, cancelled, last));
        }
        List<Row> rows = new ArrayList<>(counts.values());
        rows.sort(Comparator.<Row>comparingInt(Row::total).reversed()
                .thenComparing(Row::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Row::uuid));
        return List.copyOf(rows);
    }

    /**
     * Privacy-projected lines for the HTML/JSON/CSV export section. The caller
     * passes the record filter and the full snapshot; names/identities are
     * resolved through the same redactor and witness dictionaries as death rows.
     */
    public static List<String> exportLines(Collection<PlayerDeaths> players,
                                           Collection<PlayerDeaths> fullSnapshot) {
        List<PlayerDeaths> basis = new ArrayList<>();
        if (players != null) {
            for (PlayerDeaths player : players) {
                if (player != null) {
                    basis.add(player);
                }
            }
        }
        Collection<PlayerDeaths> full = fullSnapshot == null || fullSnapshot.isEmpty()
                ? basis : fullSnapshot;
        List<PlayerDeaths> view = DeathRedactor.publicView(basis, full);
        Map<String, String> names = WitnessRedactor.namesById(full);
        Map<String, PlayerPrivacy> privacy = WitnessRedactor.privacyById(full);
        List<Row> rows = rank(view, 0L, names, privacy, false);
        List<String> lines = new ArrayList<>(rows.size());
        for (Row row : rows) {
            lines.add(row.name() + " — " + row.total() + " saves (totem " + row.totem()
                    + ", cancelled " + row.cancelled() + ")");
        }
        return List.copyOf(lines);
    }

    /** Newest-first own history, read-only; no DeathEntry is created. */
    public static List<NearDeathEvent> history(Collection<PlayerDeaths> players, UUID uuid, long sinceMillis) {
        List<NearDeathEvent> out = new ArrayList<>();
        if (uuid == null || players == null) {
            return List.of();
        }
        for (PlayerDeaths player : players) {
            if (player == null || !uuid.toString().equalsIgnoreCase(player.uuid())) {
                continue;
            }
            for (NearDeathEvent event : player.nearDeaths()) {
                if (event != null && !event.isEmpty()
                        && (sinceMillis <= 0L || event.epochMillis() >= sinceMillis)) {
                    out.add(event);
                }
            }
        }
        out.sort(Comparator.comparingLong(NearDeathEvent::epochMillis).reversed()
                .thenComparing(NearDeathEvent::uuid));
        return List.copyOf(out);
    }

    /** Language-neutral coordinate label; hidden coordinates show the death marker. */
    public static String positionLabel(NearDeathEvent event) {
        if (event == null) {
            return DeathRedactor.COORDINATE_MARKER + ", " + DeathRedactor.COORDINATE_MARKER
                    + ", " + DeathRedactor.COORDINATE_MARKER;
        }
        if (!Double.isFinite(event.x()) || !Double.isFinite(event.y()) || !Double.isFinite(event.z())) {
            return DeathRedactor.COORDINATE_MARKER + ", " + DeathRedactor.COORDINATE_MARKER
                    + ", " + DeathRedactor.COORDINATE_MARKER;
        }
        return (int) Math.floor(event.x()) + ", " + (int) Math.floor(event.y())
                + ", " + (int) Math.floor(event.z());
    }

    /** Stable, language-neutral kind token for exports and UI fallbacks. */
    public static String kindToken(NearDeathEvent.Kind kind) {
        return kind == NearDeathEvent.Kind.TOTEM ? "TOTEM" : "CANCELLED";
    }

    private static String resolveName(PlayerDeaths player, Map<String, String> names) {
        String resolved = names.get(player.uuid());
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }
        return player.name().isEmpty() ? player.uuid() : player.name();
    }
}
