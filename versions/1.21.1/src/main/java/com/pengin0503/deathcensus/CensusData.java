package com.pengin0503.deathcensus;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        public String lastKnownName = "";
        public boolean optOutPublic = false;
        /** PLAY_TIME when first seen in this world; -1 = not set yet. */
        public int baselinePlayTicks = -1;
        /** PLAY_TIME at the most recent recorded death; -1 = no death yet. */
        public int lastDeathPlayTicks = -1;
    }

    private final Map<UUID, PlayerCensus> players = new LinkedHashMap<>();
    private long lastDigestEpochMillis = 0L;

    public static CensusData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
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
            pc.baselinePlayTicks = 0;
            pc.lastDeathPlayTicks = pd.deaths().isEmpty()
                    ? -1
                    : pd.deaths().get(pd.deaths().size() - 1).playTicks();
            players.put(uuid, pc);
        }
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
                t.contains("level", Tag.TAG_ANY_NUMERIC) ? Math.max(0, t.getInt("level")) : 0);
    }

    public static CensusData load(CompoundTag tag, HolderLookup.Provider provider) {
        CensusData data = new CensusData();
        CompoundTag all = tag.getCompound("players");
        for (String uuidKey : all.getAllKeys()) {
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidKey);
            } catch (IllegalArgumentException ignored) {
                DeathCensus.LOGGER.warn("[DeathCensus] Skipping player entry with invalid UUID '{}' in SavedData", uuidKey);
                continue;
            }
            CompoundTag entry = all.getCompound(uuidKey);
            PlayerCensus pc = new PlayerCensus();
            pc.lastKnownName = entry.getString("lastKnownName");
            pc.optOutPublic = entry.contains("optOut") && entry.getBoolean("optOut");
            pc.baselinePlayTicks = entry.contains("baseline", Tag.TAG_ANY_NUMERIC) ? entry.getInt("baseline") : -1;
            pc.lastDeathPlayTicks = entry.contains("lastDeath", Tag.TAG_ANY_NUMERIC) ? entry.getInt("lastDeath") : -1;
            if (entry.contains("deaths", Tag.TAG_LIST)) {
                for (Tag raw : entry.getList("deaths", Tag.TAG_COMPOUND)) {
                    if (raw instanceof CompoundTag deathTag) {
                        DeathEntry e = entryFromTag(uuidKey, deathTag);
                        if (e != null) {
                            pc.deaths.add(e);
                        } else {
                            DeathCensus.LOGGER.warn("[DeathCensus] Skipping malformed death entry for '{}' in SavedData", uuidKey);
                        }
                    }
                }
            }
            data.players.put(uuid, pc);
        }
        if (tag.contains("lastDigestEpoch", Tag.TAG_ANY_NUMERIC)) {
            long v = tag.getLong("lastDigestEpoch");
            if (v > 0L) {
                data.lastDigestEpochMillis = v;
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
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
            all.put(e.getKey().toString(), entry);
        }
        tag.put("players", all);
        if (lastDigestEpochMillis > 0L) {
            tag.putLong("lastDigestEpoch", lastDigestEpochMillis);
        }
        return tag;
    }
}
