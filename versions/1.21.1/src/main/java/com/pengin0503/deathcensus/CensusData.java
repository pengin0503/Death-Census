package com.pengin0503.deathcensus;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-world storage: for each player, their death records in chronological
 * order, plus privacy and survival-baseline state. Stored in the overworld's
 * data storage as "deathcensus_data".
 */
public class CensusData extends SavedData {
    static final String DATA_NAME = DeathCensus.MOD_ID + "_data";

    static final Factory<CensusData> FACTORY = new Factory<>(
            CensusData::new,
            CensusData::load,
            null);

    /** Per-player state. */
    public static final class PlayerCensus {
        public final List<DeathEntry> deaths = new ArrayList<>();
        /** §10: bounded near-death saves, separate from real deaths. Empty on old saves. */
        public final List<NearDeathEvent> nearDeaths = new ArrayList<>();
        /** Cause categories this player has ever died to (known categories only), even after record trimming. */
        public final Set<String> lifetimeCategories = new LinkedHashSet<>();
        public String lastKnownName = "";
        public boolean optOutPublic = false;
        /** Granular privacy flags (DESIGN 1.3.0 §7); all default to false on old saves. */
        public boolean hideCoordinates = false;
        public boolean hidePvpOpponent = false;
        public boolean discordOptOut = false;
        /** PLAY_TIME when first seen in this world; -1 = not set yet. */
        public int baselinePlayTicks = -1;
        /** PLAY_TIME at the most recent recorded death; -1 = no death yet. */
        public int lastDeathPlayTicks = -1;
        /** Vanilla pre-install death baseline (DESIGN 1.3.0 §9); never includes deaths recorded by this mod. */
        public int vanillaDeathsBaseline = 0;
        /** Lifetime deaths recorded by this mod for this player; survives trimming/deletion. */
        public long lifetimeRecorded = 0L;
        /** Mapped vanilla killed_by categories; diagnostic only, never merged into live cause percentages. */
        public Map<String, Integer> vanillaBaselineCauses = new LinkedHashMap<>();

        public Set<String> lifetimeCategories() {
            return Collections.unmodifiableSet(lifetimeCategories);
        }
    }

    private final Map<UUID, PlayerCensus> players = new LinkedHashMap<>();
    /** §3: server-level chronicle rows; old saves get a labelled, records-only reconstruction. */
    public final List<ChronicleEvent> chronicleEvents = new ArrayList<>();
    private long lastDigestEpochMillis = 0L;
    /** Lifetime deaths across all players; survives trimming of retained records. */
    private long lifetimeDeaths = 0L;
    /** Raw values dropped during load, written back verbatim so operators can back them up (§3.3). */
    private final Map<String, Tag> preservedPlayerValues = new LinkedHashMap<>();
    /** A malformed top-level players container must never be silently overwritten on save (B1). */
    private boolean doNotSave = false;
    @Nullable
    private CompoundTag preservedRawTag = null;

    /** Immutable view of the persisted chronicle aggregate. */
    public List<ChronicleEvent> chronicleEvents() {
        return Collections.unmodifiableList(chronicleEvents);
    }

    /**
     * Appends recorded/reconstructed rows through the PR-5 storage seam, dropping
     * the oldest beyond {@code chronicleMaxEvents} and dirtying the SavedData only
     * when the aggregate actually changes. One log per overflow batch.
     */
    public void addChronicleEvents(Collection<ChronicleEvent> additions) {
        if (additions == null || additions.isEmpty()) {
            return;
        }
        ChronicleRules.CapResult capped = ChronicleRules.appendBounded(
                chronicleEvents, additions, Config.chronicleMaxEvents());
        if (capped.dropped() > 0) {
            DeathCensus.LOGGER.info("[DeathCensus] Dropped {} oldest chronicle row(s) beyond cap {}",
                    capped.dropped(), Config.chronicleMaxEvents());
        }
        if (!chronicleEvents.equals(capped.events())) {
            chronicleEvents.clear();
            chronicleEvents.addAll(capped.events());
            setDirty();
        }
    }

    public void addChronicleEvent(ChronicleEvent event) {
        addChronicleEvents(event == null ? List.of() : List.of(event));
    }

    /** Load-only: derives the provable subset when the aggregate field is absent. */
    private static void reconstructChronicle(CensusData data, boolean lifetimeDeathsPresent,
                                             Map<String, Set<String>> storedCategories) {
        if (!Config.chronicleEnabled()) {
            return;
        }
        ChronicleRules.ReconstructionResult result = ChronicleRules.reconstruct(
                new ChronicleRules.ReconstructionInput(data.snapshot(),
                        lifetimeDeathsPresent
                                ? (int) Math.min(data.lifetimeDeaths, Integer.MAX_VALUE) : null,
                        storedCategories.isEmpty() ? null : storedCategories));
        ChronicleRules.CapResult capped = ChronicleRules.cap(
                result.events(), Config.chronicleMaxEvents());
        if (!capped.events().isEmpty()) {
            data.chronicleEvents.addAll(capped.events());
            DeathCensus.LOGGER.info("[DeathCensus] Reconstructed {} chronicle row(s) from retained records",
                    capped.events().size());
            if (capped.dropped() > 0) {
                DeathCensus.LOGGER.info("[DeathCensus] Dropped {} oldest reconstructed chronicle row(s)",
                        capped.dropped());
            }
        }
    }

    public static CensusData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** Non-creating lookup for read-only commands; does not mark SavedData dirty. */
    @Nullable
    public static CensusData getIfExists(MinecraftServer server) {
        return server.overworld().getDataStorage().get(FACTORY, DATA_NAME);
    }

    /** Raw stored counter without vanilla baselines; used by the importer to avoid double counting. */
    public long storedLifetimeDeaths() {
        return lifetimeDeaths;
    }

    /** Imported pre-install baselines only (DESIGN 1.3.0 §9). */
    public long vanillaBaselineTotal() {
        long total = 0L;
        for (PlayerCensus pc : players.values()) {
            total += pc.vanillaDeathsBaseline;
        }
        return total;
    }

    /** Milestones use this number: stored deaths plus anonymous vanilla pre-install totals. */
    public long lifetimeDeaths() {
        return lifetimeDeaths + vanillaBaselineTotal();
    }

    /**
     * PR-10 (DESIGN 1.3.0 §9): max/merge of vanilla pre-install baselines. No
     * DeathEntry is fabricated, no milestone evaluation runs and the stored
     * counter is untouched. The pre-install portion excludes deaths this mod
     * already retained for the player, so an import after real deaths (or a
     * repeated import) can neither double count nor lower a baseline. A dry
     * run computes the same report without writing anything.
     */
    public VanillaImportResult importVanillaBaselines(Map<UUID, VanillaStats> stats, boolean dryRun) {
        List<VanillaImportResult.Row> rows = new ArrayList<>();
        int skipped = 0;
        long added = 0L;
        if (stats != null) {
            for (Map.Entry<UUID, VanillaStats> entry : stats.entrySet()) {
                UUID uuid = entry.getKey();
                PlayerCensus pc = uuid == null ? null : players.get(uuid);
                if (pc == null) {
                    // Unknown UUIDs: unrelated stat files are ignored entirely.
                    continue;
                }
                VanillaStats vanilla = entry.getValue();
                int vanillaDeaths = vanilla == null ? 0 : vanilla.deaths();
                if (vanillaDeaths <= 0) {
                    skipped++;
                    continue;
                }
                long preinstall = Math.max(0L, (long) vanillaDeaths - pc.lifetimeRecorded);
                int candidate = (int) Math.min(Integer.MAX_VALUE, preinstall);
                int baseline = Math.max(pc.vanillaDeathsBaseline, candidate);
                if (baseline <= 0) {
                    skipped++;
                    continue;
                }
                Map<String, Integer> causes = new LinkedHashMap<>(pc.vanillaBaselineCauses);
                for (Map.Entry<String, Integer> cause : VanillaStatsParser.causeCounts(vanilla).entrySet()) {
                    causes.put(cause.getKey(), Math.max(causes.getOrDefault(cause.getKey(), 0), cause.getValue()));
                }
                long delta = (long) baseline - pc.vanillaDeathsBaseline;
                if (!dryRun && delta > 0L) {
                    pc.vanillaDeathsBaseline = baseline;
                    pc.vanillaBaselineCauses = causes;
                    setDirty();
                }
                added += delta;
                String top = VanillaStatsParser.topCause(vanilla);
                rows.add(new VanillaImportResult.Row(uuid.toString(), pc.lastKnownName,
                        vanillaDeaths, baseline, top.isEmpty() ? "-" : top));
            }
        }
        return new VanillaImportResult(rows, skipped, added);
    }

    public long lastDigestEpochMillis() {
        return lastDigestEpochMillis;
    }

    public void setLastDigestEpochMillis(long epochMillis) {
        long normalized = Math.max(0L, epochMillis);
        if (this.lastDigestEpochMillis != normalized) {
            this.lastDigestEpochMillis = normalized;
            setDirty();
        }
    }

    public PlayerCensus player(UUID uuid) {
        return players.computeIfAbsent(uuid, u -> {
            setDirty();
            return new PlayerCensus();
        });
    }

    @Nullable
    public PlayerCensus playerOrNull(UUID uuid) {
        return players.get(uuid);
    }

    public Map<UUID, PlayerCensus> allPlayers() {
        return Collections.unmodifiableMap(players);
    }

    public boolean isEmpty() {
        return players.isEmpty();
    }

    /**
     * Appends a death record, updates survival bookkeeping, trims the oldest
     * records beyond {@code maxPerPlayer} and marks the data dirty.
     *
     * @return how many old records were dropped by trimming
     */
    public int recordDeath(DeathEntry entry, int maxPerPlayer) {
        UUID uuid;
        try {
            uuid = UUID.fromString(entry.playerUuid());
        } catch (IllegalArgumentException e) {
            return 0;
        }
        PlayerCensus pc = player(uuid);
        pc.deaths.add(entry);
        pc.lifetimeRecorded++;
        lifetimeDeaths++;
        if (CauseClassifier.isKnownCategory(entry.causeCategory())) {
            pc.lifetimeCategories.add(entry.causeCategory());
        }
        pc.lastDeathPlayTicks = entry.playTicks();
        if (!entry.playerName().isEmpty()) {
            pc.lastKnownName = entry.playerName();
        }
        int excess = CensusLogic.excessCount(pc.deaths.size(), Math.max(1, maxPerPlayer));
        if (excess > 0) {
            pc.deaths.subList(0, excess).clear();
            DeathCensus.LOGGER.info("[DeathCensus] Trimmed {} oldest death record(s) for {} (cap {})",
                    excess, entry.playerName(), maxPerPlayer);
        }
        setDirty();
        return excess;
    }

    /**
     * Increments the F count of the death with the given timestamp for the
     * victim, replacing the immutable entry with an updated copy.
     *
     * @return true when the death was found and updated
     */
    public boolean addRespect(UUID victim, long epochMillis) {
        PlayerCensus pc = players.get(victim);
        if (pc == null) {
            return false;
        }
        for (int i = pc.deaths.size() - 1; i >= 0; i--) {
            DeathEntry e = pc.deaths.get(i);
            if (e.epochMillis() == epochMillis) {
                pc.deaths.set(i, copyWithStory(e, e.excuse(), e.lastWords(), e.fCount() + 1));
                setDirty();
                return true;
            }
        }
        return false;
    }

    /**
     * Sets the excuse on the death identified by {@code (uuid, epochMillis)}.
     * The GUI/chat excuse flow targets the selected record;
     * {@link #setExcuse(UUID, String)} keeps defaulting to the most recent death
     * for the plain {@code /dc excuse <text>} command.
     *
     * @return true when the record was found and updated
     */
    public boolean setExcuse(UUID uuid, long epochMillis, String excuse) {
        PlayerCensus pc = players.get(uuid);
        if (pc == null) {
            return false;
        }
        for (int i = pc.deaths.size() - 1; i >= 0; i--) {
            DeathEntry e = pc.deaths.get(i);
            if (e.epochMillis() == epochMillis) {
                pc.deaths.set(i, copyWithStory(e, excuse, e.lastWords(), e.fCount()));
                setDirty();
                return true;
            }
        }
        return false;
    }

    /**
     * Sets the excuse on the player's most recent death, replacing any existing
     * excuse.
     *
     * @return true when the player has a death record, false otherwise
     */
    public boolean setExcuse(UUID uuid, String excuse) {
        PlayerCensus pc = players.get(uuid);
        if (pc == null || pc.deaths.isEmpty()) {
            return false;
        }
        int last = pc.deaths.size() - 1;
        DeathEntry e = pc.deaths.get(last);
        pc.deaths.set(last, copyWithStory(e, excuse, e.lastWords(), e.fCount()));
        setDirty();
        return true;
    }

    /**
     * Removes the death at the given zero-based chronological index (oldest first),
     * updating the last-death bookkeeping and marking the data dirty.
     *
     * @return true when the player and index existed
     */
    public boolean removeDeath(UUID uuid, int indexZeroBased) {
        PlayerCensus pc = players.get(uuid);
        if (pc == null || indexZeroBased < 0 || indexZeroBased >= pc.deaths.size()) {
            return false;
        }
        pc.deaths.remove(indexZeroBased);
        pc.lastDeathPlayTicks = pc.deaths.isEmpty()
                ? -1
                : pc.deaths.get(pc.deaths.size() - 1).playTicks();
        setDirty();
        return true;
    }

    /**
     * Removes the player's whole census state: deaths, last known name, opt-out
     * flag and survival baselines.
     *
     * @return true when the player existed
     */
    public boolean removePlayer(UUID uuid) {
        if (players.remove(uuid) == null) {
            return false;
        }
        setDirty();
        return true;
    }

    /**
     * PR-11 (§10): appends one near-death save through the PR-1 seam. No
     * DeathEntry, lifetime counter, category or F count is touched: near deaths
     * are a separate, bounded counter and never affect death aggregates.
     *
     * @return false when the event is empty, malformed or deduped
     */
    public boolean addNearDeathEvent(NearDeathEvent event, int maxPerPlayer, long dedupWindowMillis) {
        if (event == null || event.isEmpty()) {
            return false;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(event.uuid());
        } catch (IllegalArgumentException e) {
            return false;
        }
        PlayerCensus pc = player(uuid);
        if (NearDeathAnalysis.isDuplicate(pc.nearDeaths, event, dedupWindowMillis)) {
            return false;
        }
        NearDeathAnalysis.CapResult capped = NearDeathAnalysis.appendBounded(
                pc.nearDeaths, List.of(event), maxPerPlayer);
        pc.nearDeaths.clear();
        pc.nearDeaths.addAll(capped.events());
        if (pc.lastKnownName.isEmpty() && !event.name().isEmpty()) {
            pc.lastKnownName = event.name();
        }
        int dropped = capped.dropped() + trimNearDeathsToServerCap();
        if (dropped > 0) {
            DeathCensus.LOGGER.info("[DeathCensus] Dropped {} oldest near-death save(s) beyond caps", dropped);
        }
        setDirty();
        return true;
    }

    /** Server-wide §10 cap (500); only the oldest near-death saves are dropped. */
    private int trimNearDeathsToServerCap() {
        int total = 0;
        for (PlayerCensus pc : players.values()) {
            total += pc.nearDeaths.size();
        }
        int excess = total - NearDeathAnalysis.SERVER_CAP;
        if (excess <= 0) {
            return 0;
        }
        List<Map.Entry<String, NearDeathEvent>> all = new ArrayList<>();
        for (Map.Entry<UUID, PlayerCensus> entry : players.entrySet()) {
            for (NearDeathEvent event : entry.getValue().nearDeaths) {
                all.add(Map.entry(entry.getKey().toString(), event));
            }
        }
        all.sort(Comparator.comparingLong((Map.Entry<String, NearDeathEvent> e) -> e.getValue().epochMillis())
                .thenComparing(Map.Entry::getKey));
        for (int i = 0; i < excess; i++) {
            Map.Entry<String, NearDeathEvent> oldest = all.get(i);
            PlayerCensus pc = players.get(UUID.fromString(oldest.getKey()));
            if (pc != null) {
                pc.nearDeaths.remove(oldest.getValue());
            }
        }
        return excess;
    }

    private static DeathEntry copyWithStory(DeathEntry e, String excuse, String lastWords, int fCount) {
        return new DeathEntry(
                e.playerUuid(),
                e.playerName(),
                e.epochMillis(),
                e.gameDay(),
                e.playTicks(),
                e.survivalTicks(),
                e.dimension(),
                e.x(),
                e.y(),
                e.z(),
                e.causeCategory(),
                e.causeId(),
                e.deathMessage(),
                e.killerType(),
                e.killerName(),
                e.gameMode(),
                e.experienceLevel(),
                excuse,
                lastWords,
                fCount);
    }

    /** Snapshot of all players as common-model {@link PlayerDeaths}. */
    public List<PlayerDeaths> snapshot() {
        List<PlayerDeaths> out = new ArrayList<>(players.size());
        for (Map.Entry<UUID, PlayerCensus> e : players.entrySet()) {
            PlayerCensus pc = e.getValue();
            out.add(new PlayerDeaths(e.getKey().toString(), pc.lastKnownName, pc.optOutPublic,
                    pc.hideCoordinates, pc.hidePvpOpponent, pc.discordOptOut, pc.deaths,
                    pc.vanillaDeathsBaseline, pc.vanillaBaselineCauses, pc.nearDeaths));
        }
        return out;
    }

    /** Replaces all stored data with the given snapshot (used by admin restore). */
    public void replaceAll(List<PlayerDeaths> snapshot) {
        replaceAll(snapshot, List.of());
    }

    /** As above, also restoring the §3 chronicle aggregate from a backup. */
    public void replaceAll(List<PlayerDeaths> snapshot, List<ChronicleEvent> chronicle) {
        players.clear();
        chronicleEvents.clear();
        chronicleEvents.addAll(ChronicleEvent.copyOf(chronicle));
        long restoredTotal = 0L;
        for (PlayerDeaths pd : snapshot) {
            UUID uuid;
            try {
                uuid = UUID.fromString(pd.uuid());
            } catch (IllegalArgumentException e) {
                continue;
            }
            PlayerCensus pc = new PlayerCensus();
            pc.lastKnownName = pd.name();
            pc.optOutPublic = pd.optOutPublic();
            pc.hideCoordinates = pd.hideCoordinates();
            pc.hidePvpOpponent = pd.hidePvpOpponent();
            pc.discordOptOut = pd.discordOptOut();
            pc.vanillaDeathsBaseline = Math.max(0, pd.vanillaDeathsBaseline());
            pc.vanillaBaselineCauses.putAll(pd.vanillaBaselineCauses());
            pc.deaths.addAll(pd.deaths());
            pc.lifetimeRecorded = pd.deaths().size();
            pc.nearDeaths.addAll(pd.nearDeaths());
            for (DeathEntry death : pd.deaths()) {
                if (CauseClassifier.isKnownCategory(death.causeCategory())) {
                    pc.lifetimeCategories.add(death.causeCategory());
                }
            }
            restoredTotal += pd.deaths().size();
            // -1 = "no baseline yet": the login handler re-baselines on next login.
            // 0 would make a no-death player's next death count their entire
            // lifetime playtime as survival. Players with deaths are governed by
            // lastDeathPlayTicks, rebuilt below.
            pc.baselinePlayTicks = -1;
            pc.lastDeathPlayTicks = pd.deaths().isEmpty()
                    ? -1
                    : pd.deaths().get(pd.deaths().size() - 1).playTicks();
            players.put(uuid, pc);
        }
        // A restored backup carries no lifetime metadata: derive the counter from
        // the restored records, just as loading old SavedData does.
        lifetimeDeaths = restoredTotal;
        setDirty();
    }

    /** Finds a player UUID by last known name (case-insensitive), or null. */
    @Nullable
    public UUID findPlayerByName(String name) {
        if (name == null) {
            return null;
        }
        for (Map.Entry<UUID, PlayerCensus> e : players.entrySet()) {
            if (name.equalsIgnoreCase(e.getValue().lastKnownName)) {
                return e.getKey();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ NBT

    private static CompoundTag entryToTag(DeathEntry e) {
        CompoundTag t = new CompoundTag();
        t.putString("name", e.playerName());
        t.putLong("epoch", e.epochMillis());
        t.putLong("day", e.gameDay());
        t.putInt("play", e.playTicks());
        t.putInt("surv", e.survivalTicks());
        t.putString("dim", e.dimension());
        t.putDouble("x", e.x());
        t.putDouble("y", e.y());
        t.putDouble("z", e.z());
        t.putString("cat", e.causeCategory());
        t.putString("cause", e.causeId());
        t.putString("msg", e.deathMessage());
        t.putString("ktype", e.killerType());
        t.putString("kname", e.killerName());
        t.putString("mode", e.gameMode());
        t.putInt("level", e.experienceLevel());
        t.putString("excuse", e.excuse());
        t.putString("lastwords", e.lastWords());
        t.putInt("fcount", e.fCount());
        // §13.2.2: additive keys are written only when non-default, so an
        // untouched 1.4.0 save stays byte-compatible with a 1.3.0 save.
        if (!e.witnesses().isEmpty()) {
            ListTag witnesses = new ListTag();
            for (WitnessRef w : e.witnesses()) {
                witnesses.add(witnessToTag(w));
            }
            t.put("witnesses", witnesses);
        }
        if (!e.context().isEmpty()) {
            t.put("context", contextToTag(e.context()));
        }
        return t;
    }

    @Nullable
    private static DeathEntry entryFromTag(String uuid, CompoundTag t) {
        if (!t.contains("epoch", Tag.TAG_ANY_NUMERIC)) {
            return null;
        }
        return new DeathEntry(
                uuid,
                t.getString("name"),
                t.getLong("epoch"),
                t.contains("day", Tag.TAG_ANY_NUMERIC) ? t.getLong("day") : 0L,
                t.contains("play", Tag.TAG_ANY_NUMERIC) ? Math.max(0, t.getInt("play")) : 0,
                t.contains("surv", Tag.TAG_ANY_NUMERIC) ? Math.max(0, t.getInt("surv")) : 0,
                t.getString("dim"),
                t.contains("x", Tag.TAG_ANY_NUMERIC) ? t.getDouble("x") : 0.0,
                t.contains("y", Tag.TAG_ANY_NUMERIC) ? t.getDouble("y") : 0.0,
                t.contains("z", Tag.TAG_ANY_NUMERIC) ? t.getDouble("z") : 0.0,
                t.getString("cat"),
                t.getString("cause"),
                t.getString("msg"),
                t.getString("ktype"),
                t.getString("kname"),
                t.getString("mode"),
                t.contains("level", Tag.TAG_ANY_NUMERIC) ? Math.max(0, t.getInt("level")) : 0,
                t.getString("excuse"),
                t.getString("lastwords"),
                t.contains("fcount", Tag.TAG_ANY_NUMERIC) ? Math.max(0, t.getInt("fcount")) : 0,
                witnessesFromTag(t),
                contextFromTag(t));
    }

    // ---- §13.2.2: additive NBT shapes with absent/per-element defaults -----

    private static CompoundTag witnessToTag(WitnessRef w) {
        CompoundTag t = new CompoundTag();
        t.putString("uuid", w.uuid());
        t.putString("nameAtDeath", w.nameAtDeath());
        return t;
    }

    @Nullable
    private static WitnessRef witnessFromTag(CompoundTag t) {
        if (!t.contains("uuid", Tag.TAG_STRING) && !t.contains("nameAtDeath", Tag.TAG_STRING)) {
            return null;
        }
        return new WitnessRef(t.getString("uuid"), t.getString("nameAtDeath"));
    }

    private static List<WitnessRef> witnessesFromTag(CompoundTag t) {
        List<WitnessRef> out = new ArrayList<>();
        if (!t.contains("witnesses", Tag.TAG_LIST) || !(t.get("witnesses") instanceof ListTag list)) {
            return out;
        }
        for (Tag raw : list) {
            WitnessRef ref = raw instanceof CompoundTag w ? witnessFromTag(w) : null;
            if (ref != null) {
                out.add(ref);
            } else {
                DeathCensus.LOGGER.warn("[DeathCensus] Skipping malformed witness in SavedData");
            }
        }
        return out;
    }

    private static CompoundTag contextToTag(DeathContext c) {
        CompoundTag t = new CompoundTag();
        t.putString("mainHand", c.mainHand());
        ListTag armor = new ListTag();
        for (String value : c.armor()) {
            armor.add(StringTag.valueOf(value));
        }
        t.put("armor", armor);
        ListTag effects = new ListTag();
        for (String value : c.effects()) {
            effects.add(StringTag.valueOf(value));
        }
        t.put("effects", effects);
        t.putString("biome", c.biome());
        t.putString("weather", c.weather());
        return t;
    }

    private static List<String> stringListFromTag(CompoundTag t, String key) {
        List<String> out = new ArrayList<>();
        if (!t.contains(key, Tag.TAG_LIST) || !(t.get(key) instanceof ListTag list)) {
            return out;
        }
        for (Tag raw : list) {
            if (raw instanceof StringTag s) {
                out.add(s.getAsString());
            }
        }
        return out;
    }

    private static DeathContext contextFromTag(CompoundTag t) {
        if (!t.contains("context", Tag.TAG_COMPOUND)) {
            return DeathContext.EMPTY;
        }
        CompoundTag c = t.getCompound("context");
        return new DeathContext(
                c.contains("mainHand", Tag.TAG_STRING) ? c.getString("mainHand") : "",
                stringListFromTag(c, "armor"),
                stringListFromTag(c, "effects"),
                c.contains("biome", Tag.TAG_STRING) ? c.getString("biome") : "",
                c.contains("weather", Tag.TAG_STRING) ? c.getString("weather") : "");
    }

    private static CompoundTag chronicleToTag(ChronicleEvent e) {
        CompoundTag t = new CompoundTag();
        t.putLong("epochMillis", e.epochMillis());
        t.putLong("gameDay", e.gameDay());
        t.putString("kind", e.kind().name());
        t.putString("uuid", e.uuid());
        t.putString("name", e.name());
        t.putString("dimension", e.dimension());
        t.putString("causeCategory", e.causeCategory());
        t.putInt("count", e.count());
        t.putBoolean("reconstructed", e.reconstructed());
        return t;
    }

    @Nullable
    private static ChronicleEvent chronicleFromTag(CompoundTag t) {
        if (!t.contains("epochMillis", Tag.TAG_ANY_NUMERIC)) {
            return null;
        }
        ChronicleEvent.Kind kind;
        try {
            kind = t.contains("kind", Tag.TAG_STRING)
                    ? ChronicleEvent.Kind.valueOf(t.getString("kind"))
                    : ChronicleEvent.Kind.SERVER_FIRST;
        } catch (IllegalArgumentException e) {
            kind = ChronicleEvent.Kind.SERVER_FIRST;
        }
        return new ChronicleEvent(
                t.getLong("epochMillis"),
                t.contains("gameDay", Tag.TAG_ANY_NUMERIC) ? t.getLong("gameDay") : 0L,
                kind,
                t.getString("uuid"), t.getString("name"), t.getString("dimension"),
                t.getString("causeCategory"),
                t.contains("count", Tag.TAG_ANY_NUMERIC) ? Math.max(0, t.getInt("count")) : 0,
                t.contains("reconstructed") && t.getBoolean("reconstructed"));
    }

    private static CompoundTag nearDeathToTag(NearDeathEvent e) {
        CompoundTag t = new CompoundTag();
        t.putString("uuid", e.uuid());
        t.putString("name", e.name());
        t.putLong("epochMillis", e.epochMillis());
        t.putString("dimension", e.dimension());
        t.putDouble("x", e.x());
        t.putDouble("y", e.y());
        t.putDouble("z", e.z());
        t.putString("causeCategory", e.causeCategory());
        t.putString("causeId", e.causeId());
        t.putString("kind", e.kind().name());
        return t;
    }

    @Nullable
    private static NearDeathEvent nearDeathFromTag(CompoundTag t) {
        if (!t.contains("epochMillis", Tag.TAG_ANY_NUMERIC)
                || !t.contains("uuid", Tag.TAG_STRING)) {
            return null;
        }
        NearDeathEvent.Kind kind;
        try {
            kind = t.contains("kind", Tag.TAG_STRING)
                    ? NearDeathEvent.Kind.valueOf(t.getString("kind"))
                    : NearDeathEvent.Kind.CANCELLED;
        } catch (IllegalArgumentException e) {
            kind = NearDeathEvent.Kind.CANCELLED;
        }
        return new NearDeathEvent(
                t.getString("uuid"), t.getString("name"), t.getLong("epochMillis"),
                t.getString("dimension"),
                t.contains("x", Tag.TAG_ANY_NUMERIC) ? t.getDouble("x") : 0.0,
                t.contains("y", Tag.TAG_ANY_NUMERIC) ? t.getDouble("y") : 0.0,
                t.contains("z", Tag.TAG_ANY_NUMERIC) ? t.getDouble("z") : 0.0,
                t.getString("causeCategory"), t.getString("causeId"), kind);
    }

    private static void initializeCategoriesFromDeaths(PlayerCensus pc) {
        for (DeathEntry death : pc.deaths) {
            if (CauseClassifier.isKnownCategory(death.causeCategory())) {
                pc.lifetimeCategories.add(death.causeCategory());
            }
        }
    }

    public static CensusData load(CompoundTag tag, HolderLookup.Provider provider) {
        CensusData data = new CensusData();
        boolean chronicleAbsent = !tag.contains("chronicleEvents", Tag.TAG_LIST);
        boolean lifetimeDeathsPresent = tag.contains("lifetimeDeaths", Tag.TAG_ANY_NUMERIC);
        Map<String, Set<String>> storedCategories = new LinkedHashMap<>();
        // A present-but-wrong-typed container is corruption, not an old save:
        // never replace it with players:{} on the next autosave (B1).
        if (tag.contains("players") && !tag.contains("players", Tag.TAG_COMPOUND)) {
            DeathCensus.LOGGER.error("[DeathCensus] Malformed 'players' container in SavedData: "
                    + "preserving the original tag and disabling saves until an operator backs it up");
            data.doNotSave = true;
            data.preservedRawTag = tag.copy();
            return data;
        }
        CompoundTag all = tag.getCompound("players");
        for (String uuidKey : all.getAllKeys()) {
            Tag rawValue = all.get(uuidKey);
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidKey);
            } catch (IllegalArgumentException ignored) {
                DeathCensus.LOGGER.warn("[DeathCensus] Skipping player entry with invalid UUID '{}' in SavedData", uuidKey);
                if (rawValue != null) {
                    data.preservedPlayerValues.put(uuidKey, rawValue.copy());
                }
                continue;
            }
            if (!(rawValue instanceof CompoundTag entry)) {
                DeathCensus.LOGGER.warn("[DeathCensus] Skipping corrupt player entry with key '{}' in SavedData", uuidKey);
                if (rawValue != null) {
                    data.preservedPlayerValues.put(uuidKey, rawValue.copy());
                }
                continue;
            }
            PlayerCensus pc = new PlayerCensus();
            pc.lastKnownName = entry.getString("lastKnownName");
            pc.optOutPublic = entry.contains("optOut") && entry.getBoolean("optOut");
            // Absent granular fields mean "all visible"; a wrong-typed field is
            // read as false by getBoolean and never fails the whole load (§3.3).
            pc.hideCoordinates = entry.contains("hideCoordinates") && entry.getBoolean("hideCoordinates");
            pc.hidePvpOpponent = entry.contains("hidePvpOpponent") && entry.getBoolean("hidePvpOpponent");
            pc.discordOptOut = entry.contains("discordOptOut") && entry.getBoolean("discordOptOut");
            pc.baselinePlayTicks = entry.contains("baseline", Tag.TAG_ANY_NUMERIC) ? entry.getInt("baseline") : -1;
            pc.lastDeathPlayTicks = entry.contains("lastDeath", Tag.TAG_ANY_NUMERIC) ? entry.getInt("lastDeath") : -1;
            if (entry.contains("vanillaBaseline", Tag.TAG_ANY_NUMERIC)) {
                pc.vanillaDeathsBaseline = Math.max(0, entry.getInt("vanillaBaseline"));
            }
            if (entry.contains("vanillaBaselineCauses", Tag.TAG_COMPOUND)) {
                CompoundTag causes = entry.getCompound("vanillaBaselineCauses");
                for (String category : causes.getAllKeys()) {
                    if (causes.contains(category, Tag.TAG_ANY_NUMERIC)) {
                        int count = Math.max(0, causes.getInt(category));
                        if (count > 0) {
                            pc.vanillaBaselineCauses.put(category, count);
                        }
                    }
                }
            }
            if (entry.contains("deaths", Tag.TAG_LIST)) {
                // Iterate the raw list: a typed getList would silently drop every
                // sibling when the stored element type is wrong (S6).
                if (entry.get("deaths") instanceof ListTag deaths) {
                    for (Tag raw : deaths) {
                        if (raw instanceof CompoundTag deathTag) {
                            DeathEntry e = entryFromTag(uuidKey, deathTag);
                            if (e != null) {
                                pc.deaths.add(e);
                            } else {
                                DeathCensus.LOGGER.warn("[DeathCensus] Skipping malformed death entry for '{}' in SavedData", uuidKey);
                            }
                        } else {
                            DeathCensus.LOGGER.warn("[DeathCensus] Skipping malformed death entry for '{}' in SavedData", uuidKey);
                        }
                    }
                } else {
                    DeathCensus.LOGGER.warn("[DeathCensus] Skipping malformed death list for '{}' in SavedData", uuidKey);
                }
            }
            if (entry.contains("lifetimeRecorded", Tag.TAG_ANY_NUMERIC)) {
                pc.lifetimeRecorded = Math.max(0L, entry.getLong("lifetimeRecorded"));
            } else {
                // Old saves carry no per-player counter: retained deaths are the
                // best available approximation (DESIGN §3.3).
                pc.lifetimeRecorded = pc.deaths.size();
            }
            if (entry.contains("nearDeaths", Tag.TAG_LIST)) {
                if (entry.get("nearDeaths") instanceof ListTag nearDeaths) {
                    for (Tag raw : nearDeaths) {
                        NearDeathEvent e = raw instanceof CompoundTag n ? nearDeathFromTag(n) : null;
                        if (e != null) {
                            pc.nearDeaths.add(e);
                        } else {
                            DeathCensus.LOGGER.warn("[DeathCensus] Skipping malformed near-death event for '{}' in SavedData", uuidKey);
                        }
                    }
                } else {
                    DeathCensus.LOGGER.warn("[DeathCensus] Skipping malformed near-death list for '{}' in SavedData", uuidKey);
                }
            }
            boolean usableCategory = false;
            if (entry.contains("lifetimeCategories", Tag.TAG_LIST)
                    && entry.get("lifetimeCategories") instanceof ListTag categoryList) {
                for (Tag raw : categoryList) {
                    if (raw instanceof StringTag categoryTag) {
                        String category = categoryTag.getAsString();
                        if (CauseClassifier.isKnownCategory(category)) {
                            pc.lifetimeCategories.add(category);
                            usableCategory = true;
                        }
                    }
                }
            }
            if (usableCategory) {
                storedCategories.put(uuidKey, Collections.unmodifiableSet(new LinkedHashSet<>(pc.lifetimeCategories)));
            } else {
                // Missing or unusable list: derive it from the retained deaths.
                initializeCategoriesFromDeaths(pc);
            }
            data.players.put(uuid, pc);
        }
        if (tag.contains("lastDigestEpoch", Tag.TAG_ANY_NUMERIC)) {
            long v = tag.getLong("lastDigestEpoch");
            if (v > 0L) {
                data.lastDigestEpochMillis = v;
            }
        }
        if (tag.contains("lifetimeDeaths", Tag.TAG_ANY_NUMERIC)) {
            long v = tag.getLong("lifetimeDeaths");
            if (v > 0L) {
                data.lifetimeDeaths = v;
            }
        }
        if (data.lifetimeDeaths <= 0L) {
            // Old data has no counter: the retained deaths are the best available approximation.
            for (PlayerCensus pc : data.players.values()) {
                data.lifetimeDeaths += pc.deaths.size();
            }
        }
        if (!chronicleAbsent) {
            if (tag.get("chronicleEvents") instanceof ListTag events) {
                for (Tag raw : events) {
                    ChronicleEvent e = raw instanceof CompoundTag c ? chronicleFromTag(c) : null;
                    if (e != null) {
                        data.chronicleEvents.add(e);
                    } else {
                        DeathCensus.LOGGER.warn("[DeathCensus] Skipping malformed chronicle event in SavedData");
                    }
                }
            } else {
                DeathCensus.LOGGER.warn("[DeathCensus] Skipping malformed chronicle list in SavedData");
            }
        } else {
            // Old save: only rows provable from retained records/counters (§3).
            reconstructChronicle(data, lifetimeDeathsPresent, storedCategories);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        if (doNotSave && preservedRawTag != null) {
            return preservedRawTag.copy();
        }
        CompoundTag all = new CompoundTag();
        for (Map.Entry<UUID, PlayerCensus> e : players.entrySet()) {
            PlayerCensus pc = e.getValue();
            CompoundTag entry = new CompoundTag();
            if (!pc.lastKnownName.isEmpty()) {
                entry.putString("lastKnownName", pc.lastKnownName);
            }
            if (pc.optOutPublic) {
                entry.putBoolean("optOut", true);
            }
            if (pc.hideCoordinates) {
                entry.putBoolean("hideCoordinates", true);
            }
            if (pc.hidePvpOpponent) {
                entry.putBoolean("hidePvpOpponent", true);
            }
            if (pc.discordOptOut) {
                entry.putBoolean("discordOptOut", true);
            }
            if (pc.baselinePlayTicks >= 0) {
                entry.putInt("baseline", pc.baselinePlayTicks);
            }
            if (pc.lastDeathPlayTicks >= 0) {
                entry.putInt("lastDeath", pc.lastDeathPlayTicks);
            }
            if (pc.vanillaDeathsBaseline > 0) {
                entry.putInt("vanillaBaseline", pc.vanillaDeathsBaseline);
            }
            if (pc.lifetimeRecorded > 0L) {
                entry.putLong("lifetimeRecorded", pc.lifetimeRecorded);
            }
            if (!pc.vanillaBaselineCauses.isEmpty()) {
                CompoundTag causes = new CompoundTag();
                for (Map.Entry<String, Integer> cause : pc.vanillaBaselineCauses.entrySet()) {
                    causes.putInt(cause.getKey(), cause.getValue());
                }
                entry.put("vanillaBaselineCauses", causes);
            }
            ListTag deaths = new ListTag();
            for (DeathEntry d : pc.deaths) {
                deaths.add(entryToTag(d));
            }
            entry.put("deaths", deaths);
            if (!pc.nearDeaths.isEmpty()) {
                ListTag nearDeaths = new ListTag();
                for (NearDeathEvent n : pc.nearDeaths) {
                    nearDeaths.add(nearDeathToTag(n));
                }
                entry.put("nearDeaths", nearDeaths);
            }
            if (!pc.lifetimeCategories.isEmpty()) {
                ListTag categories = new ListTag();
                for (String category : pc.lifetimeCategories) {
                    categories.add(StringTag.valueOf(category));
                }
                entry.put("lifetimeCategories", categories);
            }
            all.put(e.getKey().toString(), entry);
        }
        // Skipped corrupt values are written back verbatim for operator backup;
        // live data wins if an operator recreated one of those keys in memory.
        for (Map.Entry<String, Tag> e : preservedPlayerValues.entrySet()) {
            if (!all.contains(e.getKey())) {
                all.put(e.getKey(), e.getValue().copy());
            }
        }
        tag.put("players", all);
        if (lastDigestEpochMillis > 0L) {
            tag.putLong("lastDigestEpoch", lastDigestEpochMillis);
        }
        if (lifetimeDeaths > 0L) {
            tag.putLong("lifetimeDeaths", lifetimeDeaths);
        }
        if (!chronicleEvents.isEmpty()) {
            ListTag events = new ListTag();
            for (ChronicleEvent c : chronicleEvents) {
                events.add(chronicleToTag(c));
            }
            tag.put("chronicleEvents", events);
        }
        return tag;
    }
}
