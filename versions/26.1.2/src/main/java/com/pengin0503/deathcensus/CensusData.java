package com.pengin0503.deathcensus;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Per-world storage: for each player, their death records in chronological
 * order, plus privacy and survival-baseline state. Stored via a
 * {@link SavedDataType} with ID "deathcensus:deaths" (26.x storage API).
 *
 * <p>Design §3.3: corrupt entries are skipped with a warning, never fail the
 * whole load. The top-level codec therefore decodes each player and each death
 * entry independently instead of using {@code unboundedMap}/{@code listOf}
 * decoding, where one bad element would discard all sibling data.
 */
public class CensusData extends SavedData {
    private static final Identifier DATA_ID = Identifier.fromNamespaceAndPath(DeathCensus.MOD_ID, "deaths");

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

    // DeathEntry has 20 components, more than RecordCodecBuilder's 16-field
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
            b.add("excuse", ops.createString(e.excuse()));
            b.add("lastWords", ops.createString(e.lastWords()));
            b.add("fCount", ops.createInt(e.fCount()));
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
                    (int) lng(ops, map, "experienceLevel"),
                    str(ops, map, "excuse"),
                    str(ops, map, "lastWords"),
                    (int) lng(ops, map, "fCount")), input));
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

    private static <T> String stringValue(DynamicOps<T> ops, MapLike<T> map, String key) {
        T v = map.get(key);
        return v == null ? "" : ops.getStringValue(v).result().orElse("");
    }

    private static <T> long longValue(DynamicOps<T> ops, MapLike<T> map, String key) {
        T v = map.get(key);
        return v == null ? 0L : ops.getNumberValue(v).result().map(Number::longValue).orElse(0L);
    }

    private static <T> int intValue(DynamicOps<T> ops, MapLike<T> map, String key) {
        return (int) longValue(ops, map, key);
    }

    private static <T> boolean booleanValue(DynamicOps<T> ops, MapLike<T> map, String key) {
        T v = map.get(key);
        return v != null && ops.getBooleanValue(v).result().orElse(false);
    }

    private static DeathEntry withPlayerUuid(DeathEntry e, String uuid) {
        return new DeathEntry(
                uuid, e.playerName(), e.epochMillis(), e.gameDay(), e.playTicks(), e.survivalTicks(),
                e.dimension(), e.x(), e.y(), e.z(), e.causeCategory(), e.causeId(), e.deathMessage(),
                e.killerType(), e.killerName(), e.gameMode(), e.experienceLevel(), e.excuse(),
                e.lastWords(), e.fCount());
    }

    private static void initializeCategoriesFromDeaths(PlayerCensus pc) {
        for (DeathEntry death : pc.deaths) {
            if (CauseClassifier.isKnownCategory(death.causeCategory())) {
                pc.lifetimeCategories.add(death.causeCategory());
            }
        }
    }

    private static <T> T encodePlayer(DynamicOps<T> ops, PlayerCensus pc) {
        RecordBuilder<T> b = ops.mapBuilder();
        b.add("lastKnownName", ops.createString(pc.lastKnownName));
        b.add("optOutPublic", ops.createBoolean(pc.optOutPublic));
        b.add("baselinePlayTicks", ops.createInt(pc.baselinePlayTicks));
        b.add("lastDeathPlayTicks", ops.createInt(pc.lastDeathPlayTicks));
        b.add("deaths", ENTRY_CODEC.listOf().encodeStart(ops, pc.deaths));
        b.add("lifetimeCategories", Codec.STRING.listOf().encodeStart(ops, new ArrayList<>(pc.lifetimeCategories)));
        return b.build(ops.empty()).result().orElse(ops.empty());
    }

    /** @return null when the player value itself cannot be decoded as a map. */
    @Nullable
    private static <T> PlayerCensus decodePlayer(DynamicOps<T> ops, T input, String uuidKey) {
        DataResult<MapLike<T>> result = ops.getMap(input);
        if (result.result().isEmpty()) {
            return null;
        }
        MapLike<T> map = result.result().get();
        PlayerCensus pc = new PlayerCensus();
        pc.lastKnownName = stringValue(ops, map, "lastKnownName");
        pc.optOutPublic = booleanValue(ops, map, "optOutPublic");
        pc.baselinePlayTicks = intValue(ops, map, "baselinePlayTicks");
        pc.lastDeathPlayTicks = intValue(ops, map, "lastDeathPlayTicks");

        T deathsValue = map.get("deaths");
        if (deathsValue != null) {
            DataResult<Consumer<Consumer<T>>> listResult = ops.getList(deathsValue);
            if (listResult.result().isPresent()) {
                listResult.result().get().accept(element -> {
                    DataResult<DeathEntry> parsed = ENTRY_CODEC.parse(ops, element);
                    if (parsed.result().isEmpty()) {
                        DeathCensus.LOGGER.warn("[DeathCensus] Skipping corrupt death entry for '{}' in SavedData", uuidKey);
                        return;
                    }
                    DeathEntry death = parsed.result().get();
                    if (death.epochMillis() <= 0L) {
                        DeathCensus.LOGGER.warn("[DeathCensus] Skipping death entry without a valid epoch for '{}' in SavedData", uuidKey);
                        return;
                    }
                    if (death.playerUuid().isBlank()) {
                        death = withPlayerUuid(death, uuidKey);
                    }
                    pc.deaths.add(death);
                });
            } else {
                DeathCensus.LOGGER.warn("[DeathCensus] Skipping malformed death list for '{}' in SavedData", uuidKey);
            }
        }

        T categoriesValue = map.get("lifetimeCategories");
        if (categoriesValue == null) {
            initializeCategoriesFromDeaths(pc);
        } else {
            DataResult<Consumer<Consumer<T>>> categoriesResult = ops.getList(categoriesValue);
            if (categoriesResult.result().isPresent()) {
                categoriesResult.result().get().accept(element -> {
                    String category = ops.getStringValue(element).result().orElse("");
                    if (CauseClassifier.isKnownCategory(category)) {
                        pc.lifetimeCategories.add(category);
                    }
                });
            } else {
                initializeCategoriesFromDeaths(pc);
            }
        }
        return pc;
    }

    public static final Codec<CensusData> CODEC = new Codec<>() {
        @Override
        public <T> DataResult<T> encode(CensusData data, DynamicOps<T> ops, T prefix) {
            RecordBuilder<T> b = ops.mapBuilder();
            RecordBuilder<T> playersBuilder = ops.mapBuilder();
            for (Map.Entry<UUID, PlayerCensus> e : data.players.entrySet()) {
                playersBuilder.add(e.getKey().toString(), encodePlayer(ops, e.getValue()));
            }
            b.add("players", playersBuilder.build(ops.empty()));
            b.add("lastDigestEpoch", ops.createLong(data.lastDigestEpochMillis));
            b.add("lifetimeDeaths", ops.createLong(data.lifetimeDeaths));
            return b.build(prefix);
        }

        @Override
        public <T> DataResult<Pair<CensusData, T>> decode(DynamicOps<T> ops, T input) {
            return ops.getMap(input).map(map -> {
                CensusData data = new CensusData();
                T playersValue = map.get("players");
                if (playersValue != null) {
                    DataResult<MapLike<T>> playersResult = ops.getMap(playersValue);
                    if (playersResult.result().isPresent()) {
                        playersResult.result().get().entries().forEach(entry -> {
                            String key = ops.getStringValue(entry.getFirst()).result().orElse("");
                            try {
                                UUID uuid = UUID.fromString(key);
                                PlayerCensus pc = decodePlayer(ops, entry.getSecond(), key);
                                if (pc != null) {
                                    data.players.put(uuid, pc);
                                } else {
                                    DeathCensus.LOGGER.warn("[DeathCensus] Skipping corrupt player entry with key '{}' in SavedData", key);
                                }
                            } catch (IllegalArgumentException e) {
                                DeathCensus.LOGGER.warn("[DeathCensus] Skipping player entry with invalid UUID '{}' in SavedData", key);
                            }
                        });
                    } else {
                        DeathCensus.LOGGER.warn("[DeathCensus] Skipping malformed players map in SavedData");
                    }
                }
                data.lastDigestEpochMillis = Math.max(0L, longValue(ops, map, "lastDigestEpoch"));
                long storedLifetime = longValue(ops, map, "lifetimeDeaths");
                if (storedLifetime <= 0L) {
                    // Old data has no counter: the retained deaths are the best available approximation.
                    for (PlayerCensus pc : data.players.values()) {
                        storedLifetime += pc.deaths.size();
                    }
                }
                data.lifetimeDeaths = storedLifetime;
                return Pair.of(data, input);
            });
        }
    };

    static final SavedDataType<CensusData> TYPE = new SavedDataType<>(
            DATA_ID,
            CensusData::new,
            CODEC);

    private final Map<UUID, PlayerCensus> players = new LinkedHashMap<>();
    private long lastDigestEpochMillis = 0L;
    /** Lifetime deaths across all players; survives trimming of retained records. */
    private long lifetimeDeaths = 0L;

    public static CensusData get(MinecraftServer server) {
        var storage = server.getDataStorage();
        CensusData data = storage.get(TYPE);
        if (data == null) {
            data = new CensusData();
            storage.set(TYPE, data);
        }
        return data;
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
     * Appends a death record, updates survival bookkeeping and lifetime
     * milestones, trims the oldest records beyond {@code maxPerPlayer} and
     * marks the data dirty.
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
     * The GUI excuse flow targets the selected record; {@link #setExcuse(UUID, String)}
     * keeps defaulting to the most recent death for {@code /dc excuse}.
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
}
