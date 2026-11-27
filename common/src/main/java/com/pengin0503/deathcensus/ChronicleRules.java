package com.pengin0503.deathcensus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure world-chronicle rules (DESIGN 1.4.0 §3). Milestone announcements stay in
 * {@link Milestones}; this class only decides which persisted rows a death adds,
 * which safe rows old saves may reconstruct, and how stored rows are projected
 * for a public/Discord viewer. It never touches Minecraft APIs or storage.
 *
 * <p>No coordinates are stored on a {@link ChronicleEvent}, so no positional
 * disclosure is possible here.
 */
public final class ChronicleRules {
    /** Server total cadence for {@link ChronicleEvent.Kind#TOTAL_COUNT}. */
    public static final int TOTAL_COUNT_STEP = 100;
    /** Default retention bound (§13.1); version config may lower it. */
    public static final int DEFAULT_MAX_EVENTS = 200;
    /** Stored killer type for an ender-dragon kill; never localized free text. */
    public static final String END_DRAGON_KILLER_TYPE = "minecraft:ender_dragon";
    /** Dimension id of the Nether, matched case-insensitively. */
    public static final String NETHER_DIMENSION_ID = "minecraft:the_nether";
    /** Public stand-in for a hidden identity (wave-1 scrubbing path). */
    public static final String HIDDEN_NAME = HiddenIdentities.HIDDEN_MARKER;
    /** Label suffix for rows reconstructed from retained records. */
    public static final String RECONSTRUCTED_NOTE_TOKEN = "reconstructed";

    private static final Comparator<ChronicleEvent> ORDER =
            // Counter/category-derived rows have no provable date (M4): keep them
            // out of chronological placement by sorting them after dated rows.
            Comparator.comparing(ChronicleRules::isUndated)
                    .thenComparingLong(ChronicleEvent::epochMillis)
                    .thenComparingLong(ChronicleEvent::gameDay)
                    .thenComparingInt(event -> event.kind().ordinal())
                    .thenComparing(ChronicleEvent::uuid)
                    .thenComparing(ChronicleEvent::name);

    private ChronicleRules() {
    }

    /**
     * Counts a version tracker must supply from the state just before
     * {@code recordDeath}. {@code netherSeenBefore} is true when a Nether-first
     * row already exists (recorded or reconstructed), so an old save never fires
     * a duplicate "first" event on the next Nether death.
     */
    public record Counters(int totalDeathsBefore, boolean netherSeenBefore,
                           int playerCategoriesBefore, int playerCategoriesAfter) {
        public static final Counters EMPTY = new Counters(0, false, 0, 0);

        public Counters {
            totalDeathsBefore = Math.max(0, totalDeathsBefore);
            playerCategoriesBefore = Math.max(0, playerCategoriesBefore);
            playerCategoriesAfter = Math.max(0, playerCategoriesAfter);
        }
    }

    /**
     * Events caused by one newly recorded death, in stable presentation order.
     * At most five rows are produced and no work here is proportional to the
     * size of the retained history. Null input yields no events.
     */
    public static List<ChronicleEvent> eventsFor(DeathEntry entry, Counters counters) {
        if (entry == null) {
            return List.of();
        }
        Counters state = counters == null ? Counters.EMPTY : counters;
        List<ChronicleEvent> events = new ArrayList<>(5);

        if (state.totalDeathsBefore() == 0) {
            events.add(row(entry, ChronicleEvent.Kind.SERVER_FIRST, 1, true, true));
        }
        if (isNether(entry.dimension()) && !state.netherSeenBefore()) {
            events.add(row(entry, ChronicleEvent.Kind.NETHER_FIRST, 1, true, true));
        }
        if (isEnderDragon(entry)) {
            events.add(row(entry, ChronicleEvent.Kind.END_DRAGON_DEATH, 1, true, true));
        }
        int nextMultiple = (state.totalDeathsBefore() / TOTAL_COUNT_STEP + 1) * TOTAL_COUNT_STEP;
        if (state.totalDeathsBefore() < nextMultiple
                && state.totalDeathsBefore() + 1 == nextMultiple) {
            // Server totals are nameless by construction.
            events.add(new ChronicleEvent(entry.epochMillis(), entry.gameDay(),
                    ChronicleEvent.Kind.TOTAL_COUNT, "", "", "", "", nextMultiple, false));
        }
        if (state.playerCategoriesBefore() < CauseClassifier.CATEGORIES.size()
                && state.playerCategoriesAfter() == CauseClassifier.CATEGORIES.size()) {
            events.add(new ChronicleEvent(entry.epochMillis(), entry.gameDay(),
                    ChronicleEvent.Kind.CATEGORY_COMPLETE, entry.playerUuid(),
                    entry.playerName(), entry.dimension(), "",
                    CauseClassifier.CATEGORIES.size(), false));
        }
        return List.copyOf(events);
    }

    private static ChronicleEvent row(DeathEntry entry, ChronicleEvent.Kind kind,
                                      int count, boolean withDimension, boolean withCause) {
        return new ChronicleEvent(entry.epochMillis(), entry.gameDay(), kind,
                entry.playerUuid(), entry.playerName(),
                withDimension ? entry.dimension() : "",
                withCause ? entry.causeCategory() : "", count, false);
    }

    /** One validity rule for every stored chronicle reader (NBT, codec and backup; M3). */
    public static boolean isValidEpoch(long epochMillis) {
        return epochMillis > 0L;
    }

    /**
     * True for a reconstructed counter/category row whose date cannot be proved
     * from retained records (M4). Such rows carry no epoch and render without a
     * day label.
     */
    public static boolean isUndated(ChronicleEvent event) {
        return event != null && event.reconstructed() && !isValidEpoch(event.epochMillis())
                && event.count() > 0;
    }

    /** True only for the persisted dimension id, never a localized death message. */
    public static boolean isNether(String dimension) {
        return dimension != null && NETHER_DIMENSION_ID.equalsIgnoreCase(dimension.trim());
    }

    /** True only for the persisted {@code killerType}, never a player/killer name. */
    public static boolean isEnderDragon(DeathEntry entry) {
        return entry != null && END_DRAGON_KILLER_TYPE.equalsIgnoreCase(entry.killerType().trim());
    }

    /** Inputs for the explicitly partial reconstruction of old saves (§3). */
    public record ReconstructionInput(List<PlayerDeaths> players, Integer lifetimeDeaths,
                                      Map<String, Set<String>> lifetimeCategories) {
        public static final ReconstructionInput EMPTY =
                new ReconstructionInput(List.of(), null, null);

        public ReconstructionInput {
            players = players == null ? List.of()
                    : players.stream().filter(Objects::nonNull).toList();
        }
    }

    /** Reconstructed rows plus the number dropped by the retention cap. */
    public record ReconstructionResult(List<ChronicleEvent> events, int dropped) {
        public ReconstructionResult {
            events = ChronicleEvent.copyOf(events);
        }
    }

    /**
     * Derives ONLY rows provable from retained deaths and persisted counters.
     * Every row is flagged {@code reconstructed=true}; absent counters and
     * trimmed-away history produce no speculative rows.
     */
    public static ReconstructionResult reconstruct(ReconstructionInput input) {
        return reconstruct(input, DEFAULT_MAX_EVENTS);
    }

    /**
     * As above, but honors the caller's configured retention cap while deriving
     * rows. Generation stops once the cap is reached, so a crafted lifetime
     * counter near {@code Integer.MAX_VALUE} cannot force millions of event
     * allocations on load (M1). Rows whose date is not provable from retained
     * records (counter/category rows) carry no epoch (M4).
     */
    public static ReconstructionResult reconstruct(ReconstructionInput input, int maxEvents) {
        int cap = Math.max(0, maxEvents);
        ReconstructionInput state = input == null ? ReconstructionInput.EMPTY : input;
        List<DeathEntry> retained = new ArrayList<>();
        for (PlayerDeaths player : state.players()) {
            for (DeathEntry death : player.deaths()) {
                if (death != null) {
                    retained.add(death);
                }
            }
        }
        List<ChronicleEvent> events = new ArrayList<>(Math.min(cap, 16) + 4);
        int dropped = 0;
        DeathEntry earliest = retained.stream().min(ORDER_ROW).orElse(null);
        if (earliest != null) {
            if (events.size() < cap) {
                events.add(reconstructed(earliest, ChronicleEvent.Kind.SERVER_FIRST, 1));
            } else {
                dropped++;
            }
        }
        DeathEntry firstNether = retained.stream()
                .filter(death -> isNether(death.dimension())).min(ORDER_ROW).orElse(null);
        if (firstNether != null) {
            if (events.size() < cap) {
                events.add(reconstructed(firstNether, ChronicleEvent.Kind.NETHER_FIRST, 1));
            } else {
                dropped++;
            }
        }
        DeathEntry firstDragon = retained.stream()
                .filter(ChronicleRules::isEnderDragon).min(ORDER_ROW).orElse(null);
        if (firstDragon != null) {
            if (events.size() < cap) {
                events.add(reconstructed(firstDragon, ChronicleEvent.Kind.END_DRAGON_DEATH, 1));
            } else {
                dropped++;
            }
        }
        Integer lifetime = state.lifetimeDeaths();
        if (lifetime != null && lifetime > 0 && earliest != null) {
            int provableRows = lifetime / TOTAL_COUNT_STEP;
            int affordable = Math.min(provableRows, Math.max(0, cap - events.size()));
            for (int i = 1; i <= affordable; i++) {
                events.add(undated(ChronicleEvent.Kind.TOTAL_COUNT,
                        i * TOTAL_COUNT_STEP, "", ""));
            }
            dropped += provableRows - affordable;
        }
        if (state.lifetimeCategories() != null && earliest != null) {
            for (PlayerDeaths player : state.players()) {
                Set<String> held = state.lifetimeCategories().get(player.uuid());
                if (held == null || held.size() < CauseClassifier.CATEGORIES.size()) {
                    continue;
                }
                boolean all = true;
                for (String category : CauseClassifier.CATEGORIES) {
                    if (!held.contains(category.toLowerCase(Locale.ROOT))) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    if (events.size() < cap) {
                        events.add(undated(ChronicleEvent.Kind.CATEGORY_COMPLETE,
                                CauseClassifier.CATEGORIES.size(), player.uuid(), player.name()));
                    } else {
                        dropped++;
                    }
                    break;
                }
            }
        }
        CapResult capped = cap(events, cap);
        dropped += capped.dropped();
        return new ReconstructionResult(capped.events(), dropped);
    }

    private static ChronicleEvent undated(ChronicleEvent.Kind kind, int count,
                                          String uuid, String name) {
        return new ChronicleEvent(0L, 0L, kind, uuid, name, "", "", count, true);
    }

    private static final Comparator<DeathEntry> ORDER_ROW =
            Comparator.comparingLong(DeathEntry::epochMillis)
                    .thenComparingLong(DeathEntry::gameDay)
                    .thenComparing(DeathEntry::playerUuid);

    private static ChronicleEvent reconstructed(DeathEntry entry, ChronicleEvent.Kind kind, int count) {
        return new ChronicleEvent(entry.epochMillis(), entry.gameDay(), kind,
                entry.playerUuid(), entry.playerName(), entry.dimension(),
                entry.causeCategory(), count, true);
    }

    /** Chronological order, oldest first; ties are deterministic. */
    public static List<ChronicleEvent> sorted(Collection<ChronicleEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        return events.stream().filter(Objects::nonNull).sorted(ORDER).toList();
    }

    /** Result of appending events under a retention cap. */
    public record CapResult(List<ChronicleEvent> events, int dropped) {
        public CapResult {
            events = ChronicleEvent.copyOf(events);
            dropped = Math.max(0, dropped);
        }
    }

    /** Enforces {@code maxEvents}, dropping the OLDEST rows first. */
    public static CapResult cap(Collection<ChronicleEvent> events, int maxEvents) {
        List<ChronicleEvent> ordered = sorted(events);
        int limit = maxEvents < 0 ? 0 : maxEvents;
        int dropped = Math.max(0, ordered.size() - limit);
        return new CapResult(ordered.subList(dropped, ordered.size()), dropped);
    }

    /** Appends newly recorded rows, then drops the oldest to stay within the cap. */
    public static CapResult appendBounded(Collection<ChronicleEvent> stored,
                                          Collection<ChronicleEvent> additions, int maxEvents) {
        List<ChronicleEvent> all = new ArrayList<>();
        if (stored != null) {
            stored.stream().filter(Objects::nonNull).forEach(all::add);
        }
        if (additions != null) {
            additions.stream().filter(Objects::nonNull).forEach(all::add);
        }
        return cap(all, maxEvents);
    }

    /**
     * Public projection of one stored row. Opted-out players are never named and
     * hidden identities from the wave-1 snapshot dictionary are scrubbed even
     * when the stored row predates the opt-out. Self/admin keep the stored name.
     */
    public static ChronicleEvent forPublic(ChronicleEvent event, Map<String, PlayerPrivacy> privacy,
                                           HiddenIdentities hidden, boolean viewerIsSelfOrAdmin) {
        if (event == null) {
            return null;
        }
        if (event.kind() == ChronicleEvent.Kind.TOTAL_COUNT || viewerIsSelfOrAdmin) {
            return event;
        }
        PlayerPrivacy flags = privacy == null ? null : privacy.get(event.uuid());
        String name;
        if (flags != null && flags.hidesPublicIdentity()) {
            name = HIDDEN_NAME;
        } else {
            name = hidden == null ? event.name() : hidden.scrub(event.name());
        }
        if (name.equals(event.name())) {
            return event;
        }
        return new ChronicleEvent(event.epochMillis(), event.gameDay(), event.kind(),
                event.uuid(), name, event.dimension(), event.causeCategory(),
                event.count(), event.reconstructed());
    }

    /** Applies {@link #forPublic} to a list, retaining chronological order. */
    public static List<ChronicleEvent> forPublic(Collection<ChronicleEvent> events,
                                                 Map<String, PlayerPrivacy> privacy,
                                                 HiddenIdentities hidden,
                                                 boolean viewerIsSelfOrAdmin) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<ChronicleEvent> rows = new ArrayList<>(events.size());
        for (ChronicleEvent event : sorted(events)) {
            ChronicleEvent view = forPublic(event, privacy, hidden, viewerIsSelfOrAdmin);
            if (view != null) {
                rows.add(view);
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Discord projection seam (§3: no new posts today, {@code chronicleInDiscord}
     * defaults false). When a caller opts in later, a {@code discordOptOut} or
     * full opt-out player contributes no named row.
     */
    public static List<ChronicleEvent> forDiscord(Collection<ChronicleEvent> events,
                                                  Map<String, PlayerPrivacy> privacy) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        Map<String, PlayerPrivacy> flags = privacy == null ? Map.of() : privacy;
        List<ChronicleEvent> rows = new ArrayList<>();
        for (ChronicleEvent event : sorted(events)) {
            if (event.kind() != ChronicleEvent.Kind.TOTAL_COUNT) {
                PlayerPrivacy player = flags.get(event.uuid());
                if (player != null && player.suppressesRealtimeDiscord()) {
                    continue;
                }
            }
            rows.add(forPublic(event, flags, HiddenIdentities.empty(), false));
        }
        return List.copyOf(rows);
    }

    /** Stable lang-key suffix for a chronicle kind. */
    public static String langKey(ChronicleEvent.Kind kind) {
        if (kind == null) {
            return "";
        }
        return "command.deathcensus.chronicle.kind."
                + kind.name().toLowerCase(Locale.ROOT);
    }

    /** Privacy-by-uuid view of a snapshot; convenient for command/GUI callers. */
    public static Map<String, PlayerPrivacy> privacyById(Collection<PlayerDeaths> players) {
        return WitnessRedactor.privacyById(players);
    }

    /** Name-by-uuid view of a snapshot for self/admin rendering. */
    public static Map<String, String> namesById(Collection<PlayerDeaths> players) {
        Map<String, String> names = new LinkedHashMap<>(WitnessRedactor.namesById(players));
        return Map.copyOf(names);
    }
}
