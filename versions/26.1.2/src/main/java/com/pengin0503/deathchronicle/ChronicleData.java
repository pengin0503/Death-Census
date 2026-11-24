package com.pengin0503.deathchronicle;

import com.mojang.serialization.Codec;
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
 * {@link SavedDataType} with ID "deathchronicle:deaths" (26.x storage API).
 */
public class ChronicleData extends SavedData {
    private static final Identifier DATA_ID = Identifier.fromNamespaceAndPath(DeathChronicle.MOD_ID, "deaths");

    /** Per-player state. */
    public static final class PlayerChronicle {
        public final List<DeathEntry> deaths = new ArrayList<>();
        public String lastKnownName = "";
        public boolean optOutPublic = false;
        /** PLAY_TIME when first seen in this world; -1 = not set yet. */
        public int baselinePlayTicks = -1;
        /** PLAY_TIME at the most recent recorded death; -1 = no death yet. */
        public int lastDeathPlayTicks = -1;
    }

    private static final Codec<DeathEntry> ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("playerUuid").forGetter(DeathEntry::playerUuid),
            Codec.STRING.optionalFieldOf("playerName", "").forGetter(DeathEntry::playerName),
            Codec.LONG.fieldOf("epochMillis").forGetter(DeathEntry::epochMillis),
            Codec.LONG.optionalFieldOf("gameDay", 0L).forGetter(DeathEntry::gameDay),
            Codec.INT.fieldOf("playTicks").forGetter(DeathEntry::playTicks),
            Codec.INT.optionalFieldOf("survivalTicks", 0).forGetter(DeathEntry::survivalTicks),
            Codec.STRING.optionalFieldOf("dimension", "").forGetter(DeathEntry::dimension),
            Codec.DOUBLE.optionalFieldOf("x", 0.0).forGetter(DeathEntry::x),
            Codec.DOUBLE.optionalFieldOf("y", 0.0).forGetter(DeathEntry::y),
            Codec.DOUBLE.optionalFieldOf("z", 0.0).forGetter(DeathEntry::z),
            Codec.STRING.optionalFieldOf("causeCategory", "other").forGetter(DeathEntry::causeCategory),
            Codec.STRING.optionalFieldOf("causeId", "").forGetter(DeathEntry::causeId),
            Codec.STRING.optionalFieldOf("deathMessage", "").forGetter(DeathEntry::deathMessage),
            Codec.STRING.optionalFieldOf("killerType", "").forGetter(DeathEntry::killerType),
            Codec.STRING.optionalFieldOf("killerName", "").forGetter(DeathEntry::killerName),
            Codec.STRING.optionalFieldOf("gameMode", "").forGetter(DeathEntry::gameMode),
            Codec.INT.optionalFieldOf("experienceLevel", 0).forGetter(DeathEntry::experienceLevel)
    ).apply(instance, DeathEntry::new));

    private static PlayerChronicle playerFromCodec(String name, boolean optOut, int baseline, int lastDeath,
                                                     List<DeathEntry> deaths) {
        PlayerChronicle pc = new PlayerChronicle();
        pc.lastKnownName = name != null ? name : "";
        pc.optOutPublic = optOut;
        pc.baselinePlayTicks = baseline;
        pc.lastDeathPlayTicks = lastDeath;
        if (deaths != null) {
            pc.deaths.addAll(deaths);
        }
        return pc;
    }

    private static final Codec<PlayerChronicle> PLAYER_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("lastKnownName", "").forGetter(pc -> pc.lastKnownName),
            Codec.BOOL.optionalFieldOf("optOutPublic", false).forGetter(pc -> pc.optOutPublic),
            Codec.INT.optionalFieldOf("baselinePlayTicks", -1).forGetter(pc -> pc.baselinePlayTicks),
            Codec.INT.optionalFieldOf("lastDeathPlayTicks", -1).forGetter(pc -> pc.lastDeathPlayTicks),
            ENTRY_CODEC.listOf().optionalFieldOf("deaths", List.of()).forGetter(pc -> pc.deaths)
    ).apply(instance, ChronicleData::playerFromCodec));

    private static ChronicleData fromCodec(Map<String, PlayerChronicle> playersByUuid, long lastDigestEpoch) {
        ChronicleData data = new ChronicleData();
        for (Map.Entry<String, PlayerChronicle> e : playersByUuid.entrySet()) {
            try {
                data.players.put(UUID.fromString(e.getKey()), e.getValue());
            } catch (IllegalArgumentException ignored) {
                DeathChronicle.LOGGER.warn("[DeathChronicle] Skipping player entry with invalid UUID '{}' in SavedData", e.getKey());
            }
        }
        data.lastDigestEpochMillis = Math.max(0L, lastDigestEpoch);
        return data;
    }

    private Map<String, PlayerChronicle> playersByStringKey() {
        Map<String, PlayerChronicle> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, PlayerChronicle> e : players.entrySet()) {
            out.put(e.getKey().toString(), e.getValue());
        }
        return out;
    }

    public static final Codec<ChronicleData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, PLAYER_CODEC).optionalFieldOf("players", Map.of())
                    .forGetter(ChronicleData::playersByStringKey),
            Codec.LONG.lenientOptionalFieldOf("lastDigestEpoch", 0L)
                    .forGetter(d -> d.lastDigestEpochMillis)
    ).apply(instance, ChronicleData::fromCodec));

    static final SavedDataType<ChronicleData> TYPE = new SavedDataType<>(
            DATA_ID,
            ChronicleData::new,
            CODEC);

    private final Map<UUID, PlayerChronicle> players = new LinkedHashMap<>();
    private long lastDigestEpochMillis = 0L;

    public static ChronicleData get(MinecraftServer server) {
        var storage = server.getDataStorage();
        ChronicleData data = storage.get(TYPE);
        if (data == null) {
            data = new ChronicleData();
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

    public PlayerChronicle player(UUID uuid) {
        return players.computeIfAbsent(uuid, u -> {
            setDirty();
            return new PlayerChronicle();
        });
    }

    @Nullable
    public PlayerChronicle playerOrNull(UUID uuid) {
        return players.get(uuid);
    }

    public Map<UUID, PlayerChronicle> allPlayers() {
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
        PlayerChronicle pc = player(uuid);
        pc.deaths.add(entry);
        pc.lastDeathPlayTicks = entry.playTicks();
        if (!entry.playerName().isEmpty()) {
            pc.lastKnownName = entry.playerName();
        }
        int excess = ChronicleLogic.excessCount(pc.deaths.size(), Math.max(1, maxPerPlayer));
        if (excess > 0) {
            pc.deaths.subList(0, excess).clear();
            DeathChronicle.LOGGER.info("[DeathChronicle] Trimmed {} oldest death record(s) for {} (cap {})",
                    excess, entry.playerName(), maxPerPlayer);
        }
        setDirty();
        return excess;
    }

    /** Snapshot of all players as common-model {@link PlayerDeaths}. */
    public List<PlayerDeaths> snapshot() {
        List<PlayerDeaths> out = new ArrayList<>(players.size());
        for (Map.Entry<UUID, PlayerChronicle> e : players.entrySet()) {
            PlayerChronicle pc = e.getValue();
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
            PlayerChronicle pc = new PlayerChronicle();
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
        for (Map.Entry<UUID, PlayerChronicle> e : players.entrySet()) {
            if (name.equalsIgnoreCase(e.getValue().lastKnownName)) {
                return e.getKey();
            }
        }
        return null;
    }
}
