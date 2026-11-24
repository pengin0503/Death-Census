package com.pengin0503.deathcensus;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-world storage: for each player, their death records in chronological
 * order, plus privacy and survival-baseline state. Stored via a
 * {@link SavedDataType} with ID "deathcensus:deaths" (26.x storage API).
 */
public class CensusData extends SavedData {
    private static final Identifier DATA_ID = Identifier.fromNamespaceAndPath(DeathCensus.MOD_ID, "deaths");

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

    // DeathEntry has 17 components, more than RecordCodecBuilder's 16-field
    // group limit, so its codec is written manually.
    private static final Codec<DeathEntry> ENTRY_CODEC = new Codec<>() {
        @Override
        public <T> DataResult<T> encode(DeathEntry e, DynamicOps<T> ops, T prefix) {
            RecordBuilder<T> b = ops.mapBuilder();
            b.add("playerUuid", ops.createString(e.playerUuid()));
            b.add("playerName", ops.createString(e.playerName()));
            b.add("epochMillis", ops.createLong(e.epochMillis()));
            b.add("gameDay", ops.createLong(e.gameDay()));
            b.add("playTicks", ops.createInt(e.playTicks()));
            b.add("survivalTicks", ops.createInt(e.survivalTicks()));
            b.add("dimension", ops.createString(e.dimension()));
            b.add("x", ops.createDouble(e.x()));
            b.add("y", ops.createDouble(e.y()));
            b.add("z", ops.createDouble(e.z()));
            b.add("causeCategory", ops.createString(e.causeCategory()));
            b.add("causeId", ops.createString(e.causeId()));
            b.add("deathMessage", ops.createString(e.deathMessage()));
            b.add("killerType", ops.createString(e.killerType()));
            b.add("killerName", ops.createString(e.killerName()));
            b.add("gameMode", ops.createString(e.gameMode()));
            b.add("experienceLevel", ops.createInt(e.experienceLevel()));
            return b.build(prefix);
        }

        @Override
        public <T> DataResult<Pair<DeathEntry, T>> decode(DynamicOps<T> ops, T input) {
            return ops.getMap(input).map(map -> Pair.of(new DeathEntry(
                    str(ops, map, "playerUuid"),
                    str(ops, map, "playerName"),
                    lng(ops, map, "epochMillis"),
                    lng(ops, map, "gameDay"),
                    (int) lng(ops, map, "playTicks"),
                    (int) lng(ops, map, "survivalTicks"),
                    str(ops, map, "dimension"),
                    dbl(ops, map, "x"),
                    dbl(ops, map, "y"),
                    dbl(ops, map, "z"),
                    str(ops, map, "causeCategory"),
                    str(ops, map, "causeId"),
                    str(ops, map, "deathMessage"),
                    str(ops, map, "killerType"),
                    str(ops, map, "killerName"),
                    str(ops, map, "gameMode"),
                    (int) lng(ops, map, "experienceLevel")), input));
        }

        private <T> String str(DynamicOps<T> ops, MapLike<T> map, String key) {
            T v = map.get(key);
            return v == null ? "" : ops.getStringValue(v).result().orElse("");
        }

        private <T> long lng(DynamicOps<T> ops, MapLike<T> map, String key) {
            T v = map.get(key);
            return v == null ? 0L : ops.getNumberValue(v).result().map(Number::longValue).orElse(0L);
        }

        private <T> double dbl(DynamicOps<T> ops, MapLike<T> map, String key) {
            T v = map.get(key);
            return v == null ? 0.0 : ops.getNumberValue(v).result().map(Number::doubleValue).orElse(0.0);
        }
    };

    private static PlayerCensus playerFromCodec(String name, boolean optOut, int baseline, int lastDeath,
                                                     List<DeathEntry> deaths) {
        PlayerCensus pc = new PlayerCensus();
        pc.lastKnownName = name != null ? name : "";
        pc.optOutPublic = optOut;
        pc.baselinePlayTicks = baseline;
        pc.lastDeathPlayTicks = lastDeath;
        if (deaths != null) {
            pc.deaths.addAll(deaths);
        }
        return pc;
    }

    private static final Codec<PlayerCensus> PLAYER_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.lenientOptionalFieldOf("lastKnownName", "").forGetter(pc -> pc.lastKnownName),
            Codec.BOOL.lenientOptionalFieldOf("optOutPublic", false).forGetter(pc -> pc.optOutPublic),
            Codec.INT.lenientOptionalFieldOf("baselinePlayTicks", -1).forGetter(pc -> pc.baselinePlayTicks),
            Codec.INT.lenientOptionalFieldOf("lastDeathPlayTicks", -1).forGetter(pc -> pc.lastDeathPlayTicks),
            ENTRY_CODEC.listOf().lenientOptionalFieldOf("deaths", List.of()).forGetter(pc -> pc.deaths)
    ).apply(instance, CensusData::playerFromCodec));

    private static CensusData fromCodec(Map<String, PlayerCensus> playersByUuid, long lastDigestEpoch) {
        CensusData data = new CensusData();
        for (Map.Entry<String, PlayerCensus> e : playersByUuid.entrySet()) {
            try {
                data.players.put(UUID.fromString(e.getKey()), e.getValue());
            } catch (IllegalArgumentException ignored) {
                DeathCensus.LOGGER.warn("[DeathCensus] Skipping player entry with invalid UUID '{}' in SavedData", e.getKey());
            }
        }
        data.lastDigestEpochMillis = Math.max(0L, lastDigestEpoch);
        return data;
    }

    private Map<String, PlayerCensus> playersByStringKey() {
        Map<String, PlayerCensus> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, PlayerCensus> e : players.entrySet()) {
            out.put(e.getKey().toString(), e.getValue());
        }
        return out;
    }

    public static final Codec<CensusData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, PLAYER_CODEC).optionalFieldOf("players", Map.of())
                    .forGetter(CensusData::playersByStringKey),
            Codec.LONG.lenientOptionalFieldOf("lastDigestEpoch", 0L)
                    .forGetter(d -> d.lastDigestEpochMillis)
    ).apply(instance, CensusData::fromCodec));

    static final SavedDataType<CensusData> TYPE = new SavedDataType<>(
            DATA_ID,
            CensusData::new,
            CODEC);

    private final Map<UUID, PlayerCensus> players = new LinkedHashMap<>();
    private long lastDigestEpochMillis = 0L;

    public static CensusData get(MinecraftServer server) {
        var storage = server.getDataStorage();
        CensusData data = storage.get(TYPE);
        if (data == null) {
            data = new CensusData();
            storage.set(TYPE, data);
        }
        return data;
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
}
