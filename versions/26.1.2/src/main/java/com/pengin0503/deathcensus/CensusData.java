package com.pengin0503.deathcensus;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import net.minecraft.nbt.Tag;
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

    // ---- §13.2.3: additive codecs. Missing keys decode to empty/defaults and
    // one bad element is skipped instead of failing its death or player. -----

    private static final Codec<WitnessRef> WITNESS_CODEC = new Codec<>() {
        @Override
        public <T> DataResult<T> encode(WitnessRef w, DynamicOps<T> ops, T prefix) {
            return ops.mapBuilder()
                    .add("uuid", ops.createString(w.uuid()))
                    .add("nameAtDeath", ops.createString(w.nameAtDeath()))
                    .build(prefix);
        }

        @Override
        public <T> DataResult<Pair<WitnessRef, T>> decode(DynamicOps<T> ops, T input) {
            return ops.getMap(input).flatMap(map -> {
                WitnessRef ref = new WitnessRef(
                        stringValue(ops, map, "uuid"), stringValue(ops, map, "nameAtDeath"));
                return ref.isEmpty()
                        ? DataResult.error(() -> "Empty witness entry")
                        : DataResult.success(Pair.of(ref, input));
            });
        }
    };

    private static final Codec<DeathContext> CONTEXT_CODEC = new Codec<>() {
        @Override
        public <T> DataResult<T> encode(DeathContext c, DynamicOps<T> ops, T prefix) {
            return ops.mapBuilder()
                    .add("mainHand", ops.createString(c.mainHand()))
                    .add("armor", Codec.STRING.listOf().encodeStart(ops, c.armor()))
                    .add("effects", Codec.STRING.listOf().encodeStart(ops, c.effects()))
                    .add("biome", ops.createString(c.biome()))
                    .add("weather", ops.createString(c.weather()))
                    .build(prefix);
        }

        @Override
        public <T> DataResult<Pair<DeathContext, T>> decode(DynamicOps<T> ops, T input) {
            return ops.getMap(input).map(map -> Pair.of(new DeathContext(
                    stringValue(ops, map, "mainHand"),
                    stringListValue(ops, map, "armor"),
                    stringListValue(ops, map, "effects"),
                    stringValue(ops, map, "biome"),
                    stringValue(ops, map, "weather")), input));
        }
    };

    private static final Codec<ChronicleEvent> CHRONICLE_CODEC = new Codec<>() {
        @Override
        public <T> DataResult<T> encode(ChronicleEvent e, DynamicOps<T> ops, T prefix) {
            return ops.mapBuilder()
                    .add("epochMillis", ops.createLong(e.epochMillis()))
                    .add("gameDay", ops.createLong(e.gameDay()))
                    .add("kind", ops.createString(e.kind().name()))
                    .add("uuid", ops.createString(e.uuid()))
                    .add("name", ops.createString(e.name()))
                    .add("dimension", ops.createString(e.dimension()))
                    .add("causeCategory", ops.createString(e.causeCategory()))
                    .add("count", ops.createInt(e.count()))
                    .add("reconstructed", ops.createBoolean(e.reconstructed()))
                    .build(prefix);
        }

        @Override
        public <T> DataResult<Pair<ChronicleEvent, T>> decode(DynamicOps<T> ops, T input) {
            return ops.getMap(input).flatMap(map -> {
                ChronicleEvent event = new ChronicleEvent(
                        longValue(ops, map, "epochMillis"),
                        longValue(ops, map, "gameDay"),
                        enumValue(ops, map, "kind", ChronicleEvent.Kind.class, ChronicleEvent.Kind.SERVER_FIRST),
                        stringValue(ops, map, "uuid"),
                        stringValue(ops, map, "name"),
                        stringValue(ops, map, "dimension"),
                        stringValue(ops, map, "causeCategory"),
                        intValue(ops, map, "count"),
                        booleanValue(ops, map, "reconstructed"));
                return event.epochMillis() <= 0L
                        ? DataResult.error(() -> "Chronicle event without a valid epoch")
                        : DataResult.success(Pair.of(event, input));
            });
        }
    };

    private static final Codec<NearDeathEvent> NEAR_DEATH_CODEC = new Codec<>() {
        @Override
        public <T> DataResult<T> encode(NearDeathEvent e, DynamicOps<T> ops, T prefix) {
            return ops.mapBuilder()
                    .add("uuid", ops.createString(e.uuid()))
                    .add("name", ops.createString(e.name()))
                    .add("epochMillis", ops.createLong(e.epochMillis()))
                    .add("dimension", ops.createString(e.dimension()))
                    .add("x", ops.createDouble(e.x()))
                    .add("y", ops.createDouble(e.y()))
                    .add("z", ops.createDouble(e.z()))
                    .add("causeCategory", ops.createString(e.causeCategory()))
                    .add("causeId", ops.createString(e.causeId()))
                    .add("kind", ops.createString(e.kind().name()))
                    .build(prefix);
        }

        @Override
        public <T> DataResult<Pair<NearDeathEvent, T>> decode(DynamicOps<T> ops, T input) {
            return ops.getMap(input).flatMap(map -> {
                NearDeathEvent event = new NearDeathEvent(
                        stringValue(ops, map, "uuid"),
                        stringValue(ops, map, "name"),
                        longValue(ops, map, "epochMillis"),
                        stringValue(ops, map, "dimension"),
                        dbl(ops, map, "x"), dbl(ops, map, "y"), dbl(ops, map, "z"),
                        stringValue(ops, map, "causeCategory"),
                        stringValue(ops, map, "causeId"),
                        enumValue(ops, map, "kind", NearDeathEvent.Kind.class, NearDeathEvent.Kind.CANCELLED));
                return event.epochMillis() <= 0L || event.uuid().isEmpty()
                        ? DataResult.error(() -> "Near-death event without uuid/epoch")
                        : DataResult.success(Pair.of(event, input));
            });
        }
    };

    // DeathEntry's 20 existing components exceed RecordCodecBuilder's 16-field
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
            if (!e.witnesses().isEmpty()) {
                b.add("witnesses", WITNESS_CODEC.listOf().encodeStart(ops, e.witnesses()));
            }
            if (!e.context().isEmpty()) {
                b.add("context", CONTEXT_CODEC.encodeStart(ops, e.context()));
            }
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
                    (int) lng(ops, map, "fCount"),
                    decodeList(ops, map, "witnesses", WITNESS_CODEC, "witness"),
                    contextValue(ops, map)), input));
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

    /** Explicit fallback for fields where -1 means "not set" (M2). */
    private static <T> int intValueOr(DynamicOps<T> ops, MapLike<T> map, String key, int fallback) {
        T v = map.get(key);
        return v == null ? fallback
                : ops.getNumberValue(v).result().map(n -> (int) n.longValue()).orElse(fallback);
    }

    private static <T> boolean booleanValue(DynamicOps<T> ops, MapLike<T> map, String key) {
        T v = map.get(key);
        return v != null && ops.getBooleanValue(v).result().orElse(false);
    }

    private static <T> double dbl(DynamicOps<T> ops, MapLike<T> map, String key) {
        T v = map.get(key);
        return v == null ? 0.0 : ops.getNumberValue(v).result().map(Number::doubleValue).orElse(0.0);
    }

    /** Absent key → empty list; a bad element is skipped with a warning, siblings survive. */
    private static <T, V> List<V> decodeList(DynamicOps<T> ops, MapLike<T> map, String key,
                                             Codec<V> codec, String label) {
        List<V> out = new ArrayList<>();
        T value = map.get(key);
        if (value == null) {
            return out;
        }
        DataResult<Consumer<Consumer<T>>> listResult = ops.getList(value);
        if (listResult.result().isEmpty()) {
            DeathCensus.LOGGER.warn("[DeathCensus] Skipping malformed {} list in SavedData", label);
            return out;
        }
        listResult.result().get().accept(element -> {
            DataResult<V> parsed = codec.parse(ops, element);
            if (parsed.result().isPresent()) {
                out.add(parsed.result().get());
            } else {
                DeathCensus.LOGGER.warn("[DeathCensus] Skipping corrupt {} entry in SavedData", label);
            }
        });
        return out;
    }

    private static <T> List<String> stringListValue(DynamicOps<T> ops, MapLike<T> map, String key) {
        List<String> out = new ArrayList<>();
        T value = map.get(key);
        if (value == null) {
            return out;
        }
        DataResult<Consumer<Consumer<T>>> listResult = ops.getList(value);
        if (listResult.result().isEmpty()) {
            DeathCensus.LOGGER.warn("[DeathCensus] Skipping malformed {} list in SavedData", key);
            return out;
        }
        listResult.result().get().accept(element -> {
            String parsed = ops.getStringValue(element).result().orElse("");
            if (!parsed.isEmpty()) {
                out.add(parsed);
            }
        });
        return out;
    }

    /** Missing or malformed context decodes to the immutable EMPTY singleton. */
    private static <T> DeathContext contextValue(DynamicOps<T> ops, MapLike<T> map) {
        T value = map.get("context");
        if (value == null) {
            return DeathContext.EMPTY;
        }
        DataResult<MapLike<T>> result = ops.getMap(value);
        if (result.result().isEmpty()) {
            DeathCensus.LOGGER.warn("[DeathCensus] Skipping malformed context in SavedData");
            return DeathContext.EMPTY;
        }
        MapLike<T> c = result.result().get();
        return new DeathContext(
                stringValue(ops, c, "mainHand"),
                stringListValue(ops, c, "armor"),
                stringListValue(ops, c, "effects"),
                stringValue(ops, c, "biome"),
                stringValue(ops, c, "weather"));
    }

    private static <T, E extends Enum<E>> E enumValue(DynamicOps<T> ops, MapLike<T> map, String key,
                                                       Class<E> type, E fallback) {
        try {
            return Enum.valueOf(type, stringValue(ops, map, key));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static DeathEntry withPlayerUuid(DeathEntry e, String uuid) {
        return new DeathEntry(
                uuid, e.playerName(), e.epochMillis(), e.gameDay(), e.playTicks(), e.survivalTicks(),
                e.dimension(), e.x(), e.y(), e.z(), e.causeCategory(), e.causeId(), e.deathMessage(),
                e.killerType(), e.killerName(), e.gameMode(), e.experienceLevel(), e.excuse(),
                e.lastWords(), e.fCount(), e.witnesses(), e.context());
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
        b.add("hideCoordinates", ops.createBoolean(pc.hideCoordinates));
        b.add("hidePvpOpponent", ops.createBoolean(pc.hidePvpOpponent));
        b.add("discordOptOut", ops.createBoolean(pc.discordOptOut));
        b.add("baselinePlayTicks", ops.createInt(pc.baselinePlayTicks));
        b.add("lastDeathPlayTicks", ops.createInt(pc.lastDeathPlayTicks));
        b.add("vanillaDeathsBaseline", ops.createInt(pc.vanillaDeathsBaseline));
        b.add("lifetimeRecorded", ops.createLong(pc.lifetimeRecorded));
        RecordBuilder<T> causesBuilder = ops.mapBuilder();
        for (Map.Entry<String, Integer> cause : pc.vanillaBaselineCauses.entrySet()) {
            causesBuilder.add(cause.getKey(), ops.createInt(cause.getValue()));
        }
        b.add("vanillaBaselineCauses", causesBuilder.build(ops.empty()));
        b.add("deaths", ENTRY_CODEC.listOf().encodeStart(ops, pc.deaths));
        b.add("lifetimeCategories", Codec.STRING.listOf().encodeStart(ops, new ArrayList<>(pc.lifetimeCategories)));
        if (!pc.nearDeaths.isEmpty()) {
            b.add("nearDeaths", NEAR_DEATH_CODEC.listOf().encodeStart(ops, pc.nearDeaths));
        }
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
        // Old saves have no granular fields; booleanValue defaults missing and
        // wrong-typed values to false without discarding the player (§3.3).
        pc.hideCoordinates = booleanValue(ops, map, "hideCoordinates");
        pc.hidePvpOpponent = booleanValue(ops, map, "hidePvpOpponent");
        pc.discordOptOut = booleanValue(ops, map, "discordOptOut");
        pc.baselinePlayTicks = intValueOr(ops, map, "baselinePlayTicks", -1);
        pc.lastDeathPlayTicks = intValueOr(ops, map, "lastDeathPlayTicks", -1);
        pc.vanillaDeathsBaseline = Math.max(0, intValue(ops, map, "vanillaDeathsBaseline"));
        T causesValue = map.get("vanillaBaselineCauses");
        if (causesValue != null) {
            DataResult<MapLike<T>> causesResult = ops.getMap(causesValue);
            if (causesResult.result().isPresent()) {
                causesResult.result().get().entries().forEach(cause -> {
                    String category = ops.getStringValue(cause.getFirst()).result().orElse("");
                    int count = (int) Math.max(0L,
                            ops.getNumberValue(cause.getSecond()).result().map(Number::longValue).orElse(0L));
                    if (count > 0) {
                        pc.vanillaBaselineCauses.put(category, count);
                    }
                });
            }
        }

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
                    if (death.playerUuid().isBlank()) {
                        death = withPlayerUuid(death, uuidKey);
                    }
                    pc.deaths.add(death);
                });
            } else {
                DeathCensus.LOGGER.warn("[DeathCensus] Skipping malformed death list for '{}' in SavedData", uuidKey);
            }
        }
        T recordedValue = map.get("lifetimeRecorded");
        if (recordedValue == null) {
            // Old saves carry no per-player counter: retained deaths are the
            // best available approximation (DESIGN §3.3).
            pc.lifetimeRecorded = pc.deaths.size();
        } else {
            pc.lifetimeRecorded = Math.max(0L, ops.getNumberValue(recordedValue).result()
                    .map(Number::longValue).orElse((long) pc.deaths.size()));
        }
        // §10: absent or corrupt near-death history never drops the player.
        pc.nearDeaths.addAll(decodeList(ops, map, "nearDeaths", NEAR_DEATH_CODEC, "near-death event"));

        // A list with no usable string categories is equivalent to a missing one (M1).
        List<String> usableCategories = new ArrayList<>();
        T categoriesValue = map.get("lifetimeCategories");
        if (categoriesValue != null) {
            DataResult<Consumer<Consumer<T>>> categoriesResult = ops.getList(categoriesValue);
            if (categoriesResult.result().isPresent()) {
                categoriesResult.result().get().accept(element -> {
                    String category = ops.getStringValue(element).result().orElse("");
                    if (CauseClassifier.isKnownCategory(category)) {
                        usableCategories.add(category);
                    }
                });
            }
        }
        if (usableCategories.isEmpty()) {
            initializeCategoriesFromDeaths(pc);
        } else {
            pc.lifetimeCategories.addAll(usableCategories);
        }
        return pc;
    }

    /** NBT values are copied so later mutations of a parsed input cannot change preserved data. */
    private static Object preservedCopy(Object value) {
        return value instanceof Tag tag ? tag.copy() : value;
    }

    public static final Codec<CensusData> CODEC = new Codec<>() {
        @Override
        public <T> DataResult<T> encode(CensusData data, DynamicOps<T> ops, T prefix) {
            if (data.doNotSave && data.preservedRawInput != null) {
                @SuppressWarnings("unchecked") T raw = (T) data.preservedRawInput;
                return DataResult.success(raw);
            }
            RecordBuilder<T> b = ops.mapBuilder();
            RecordBuilder<T> playersBuilder = ops.mapBuilder();
            for (Map.Entry<UUID, PlayerCensus> e : data.players.entrySet()) {
                playersBuilder.add(e.getKey().toString(), encodePlayer(ops, e.getValue()));
            }
            for (Map.Entry<String, Object> e : data.preservedPlayerValues.entrySet()) {
                if (data.players.keySet().stream().noneMatch(u -> u.toString().equals(e.getKey()))) {
                    @SuppressWarnings("unchecked") T raw = (T) e.getValue();
                    playersBuilder.add(e.getKey(), raw);
                }
            }
            b.add("players", playersBuilder.build(ops.empty()));
            b.add("lastDigestEpoch", ops.createLong(data.lastDigestEpochMillis));
            b.add("lifetimeDeaths", ops.createLong(data.lifetimeDeaths));
            if (!data.chronicleEvents.isEmpty()) {
                b.add("chronicleEvents", CHRONICLE_CODEC.listOf().encodeStart(ops, data.chronicleEvents));
            }
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
                                    data.preservedPlayerValues.put(key, preservedCopy(entry.getSecond()));
                                }
                            } catch (IllegalArgumentException e) {
                                DeathCensus.LOGGER.warn("[DeathCensus] Skipping player entry with invalid UUID '{}' in SavedData", key);
                                data.preservedPlayerValues.put(key, preservedCopy(entry.getSecond()));
                            }
                        });
                    } else {
                        // A present-but-wrong-typed container is corruption, not an
                        // old save: keep the raw input and refuse to overwrite it (B1).
                        DeathCensus.LOGGER.error("[DeathCensus] Malformed 'players' map in SavedData: "
                                + "preserving the original data and disabling saves until an operator backs it up");
                        data.doNotSave = true;
                        data.preservedRawInput = preservedCopy(input);
                        return Pair.of(data, input);
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
                data.chronicleEvents.addAll(decodeList(ops, map, "chronicleEvents",
                        CHRONICLE_CODEC, "chronicle event"));
                return Pair.of(data, input);
            });
        }
    };

    static final SavedDataType<CensusData> TYPE = new SavedDataType<>(
            DATA_ID,
            CensusData::new,
            CODEC);

    private final Map<UUID, PlayerCensus> players = new LinkedHashMap<>();
    /** §3: server-level chronicle rows; empty on old saves, nothing reconstructed in PR-1. */
    public final List<ChronicleEvent> chronicleEvents = new ArrayList<>();
    private long lastDigestEpochMillis = 0L;
    /** Lifetime deaths across all players; survives trimming of retained records. */
    private long lifetimeDeaths = 0L;
    /** Raw values dropped during load, written back verbatim so operators can back them up (§3.3). */
    private final Map<String, Object> preservedPlayerValues = new LinkedHashMap<>();
    /** A malformed top-level players container must never be silently overwritten on encode (B1). */
    private boolean doNotSave = false;
    @Nullable
    private Object preservedRawInput = null;

    /** Immutable view of the persisted chronicle aggregate. */
    public List<ChronicleEvent> chronicleEvents() {
        return Collections.unmodifiableList(chronicleEvents);
    }

    public static CensusData get(MinecraftServer server) {
        var storage = server.getDataStorage();
        CensusData data = storage.get(TYPE);
        if (data == null) {
            data = new CensusData();
            storage.set(TYPE, data);
        }
        return data;
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
                fCount,
                e.witnesses(),
                e.context());
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
}
