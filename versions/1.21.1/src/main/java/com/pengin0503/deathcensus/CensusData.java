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
import java.util.Collections;
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
        /** Cause categories this player has ever died to (known categories only), even after record trimming. */
        public final Set<String> lifetimeCategories = new LinkedHashSet<>();
        public String lastKnownName = "";
        public boolean optOutPublic = false;
        /** PLAY_TIME when first seen in this world; -1 = not set yet. */
        public int baselinePlayTicks = -1;
        /** PLAY_TIME at the most recent recorded death; -1 = no death yet. */
        public int lastDeathPlayTicks = -1;

        public Set<String> lifetimeCategories() {
            return Collections.unmodifiableSet(lifetimeCategories);
        }
    }

    private final Map<UUID, PlayerCensus> players = new LinkedHashMap<>();
    private long lastDigestEpochMillis = 0L;
    /** Lifetime deaths across all players; survives trimming of retained records. */
    private long lifetimeDeaths = 0L;
    /** Raw values dropped during load, written back verbatim so operators can back them up (§3.3). */
    private final Map<String, Tag> preservedPlayerValues = new LinkedHashMap<>();
    /** A malformed top-level players container must never be silently overwritten on save (B1). */
    private boolean doNotSave = false;
    @Nullable
    private CompoundTag preservedRawTag = null;

    public static CensusData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public long lifetimeDeaths() {
        return lifetimeDeaths;
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
            out.add(new PlayerDeaths(e.getKey().toString(), pc.lastKnownName, pc.optOutPublic, pc.deaths));
        }
        return out;
    }

    /** Replaces all stored data with the given snapshot (used by admin restore). */
    public void replaceAll(List<PlayerDeaths> snapshot) {
        players.clear();
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
            pc.deaths.addAll(pd.deaths());
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
                t.contains("fcount", Tag.TAG_ANY_NUMERIC) ? Math.max(0, t.getInt("fcount")) : 0);
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
            pc.baselinePlayTicks = entry.contains("baseline", Tag.TAG_ANY_NUMERIC) ? entry.getInt("baseline") : -1;
            pc.lastDeathPlayTicks = entry.contains("lastDeath", Tag.TAG_ANY_NUMERIC) ? entry.getInt("lastDeath") : -1;
            if (entry.contains("deaths")) {
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
            if (!usableCategory) {
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
            if (pc.baselinePlayTicks >= 0) {
                entry.putInt("baseline", pc.baselinePlayTicks);
            }
            if (pc.lastDeathPlayTicks >= 0) {
                entry.putInt("lastDeath", pc.lastDeathPlayTicks);
            }
            ListTag deaths = new ListTag();
            for (DeathEntry d : pc.deaths) {
                deaths.add(entryToTag(d));
            }
            entry.put("deaths", deaths);
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
        return tag;
    }
}
