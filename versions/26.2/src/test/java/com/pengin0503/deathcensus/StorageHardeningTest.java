package com.pengin0503.deathcensus;

import com.mojang.serialization.DynamicOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Storage hardening regressions (review B1, S5, M1, M2, M3, S1). */
class StorageHardeningTest {
    private static final DynamicOps<Tag> OPS = NbtOps.INSTANCE;
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static DeathEntry death(UUID uuid, long epoch, String category) {
        return new DeathEntry(uuid.toString(), "Alex", epoch, 3L, 500, 400,
                "minecraft:overworld", 1.0, 64.0, 2.0, category, "minecraft:" + category,
                "Alex died", "", "", "survival", 5, "", "", 0);
    }

    @Test
    void malformedPlayersMapPreservesRawInputOnEncode() {
        CompoundTag raw = new CompoundTag();
        raw.putString("players", "not a map");
        raw.putLong("lifetimeDeaths", 42L);
        raw.putString("marker", "keep me");

        CensusData loaded = CensusData.CODEC.parse(OPS, raw).getOrThrow();
        assertTrue(loaded.isEmpty());
        Tag encoded = CensusData.CODEC.encodeStart(OPS, loaded).getOrThrow();
        assertEquals(raw, encoded);
        assertEquals("not a map", ((CompoundTag) encoded).getStringOr("players", ""));
        assertEquals("keep me", ((CompoundTag) encoded).getStringOr("marker", ""));
    }

    @Test
    void absentPlayersMapLoadsEmptyAndDoesNotBlockEncode() {
        CensusData loaded = CensusData.CODEC.parse(OPS, new CompoundTag()).getOrThrow();
        assertTrue(loaded.isEmpty());
        CompoundTag encoded = (CompoundTag) CensusData.CODEC.encodeStart(OPS, loaded).getOrThrow();
        assertTrue(encoded.getCompoundOrEmpty("players").isEmpty());
    }

    @Test
    void wrongTypedPlayerValuesArePreservedWhileValidSiblingLoads() {
        CensusData data = new CensusData();
        data.recordDeath(death(PLAYER, 100L, "fall"), 100);
        CompoundTag root = (CompoundTag) CensusData.CODEC.encodeStart(OPS, data).getOrThrow();
        CompoundTag players = root.getCompoundOrEmpty("players");
        players.put(OTHER.toString(), StringTag.valueOf("corrupt player"));
        players.put("not-a-uuid", StringTag.valueOf("corrupt key"));

        CensusData loaded = CensusData.CODEC.parse(OPS, root).getOrThrow();
        assertEquals(1, loaded.playerOrNull(PLAYER).deaths.size());
        assertNull(loaded.playerOrNull(OTHER));
        assertEquals(1, loaded.allPlayers().size(), "invalid UUID must not load as a player");

        CompoundTag saved = (CompoundTag) CensusData.CODEC.encodeStart(OPS, loaded).getOrThrow();
        assertEquals("corrupt player", saved.getCompoundOrEmpty("players").getStringOr(OTHER.toString(), ""));
        assertEquals("corrupt key", saved.getCompoundOrEmpty("players").getStringOr("not-a-uuid", ""));
        assertEquals(1, saved.getCompoundOrEmpty("players").getCompoundOrEmpty(PLAYER.toString())
                .getListOrEmpty("deaths").size());
    }

    @Test
    void lifetimeCategoriesWithNoUsableStringsFallsBackToDeaths() {
        CensusData data = new CensusData();
        data.recordDeath(death(PLAYER, 100L, "fall"), 100);
        data.recordDeath(death(OTHER, 200L, "lava"), 100);
        CompoundTag root = (CompoundTag) CensusData.CODEC.encodeStart(OPS, data).getOrThrow();
        CompoundTag players = root.getCompoundOrEmpty("players");

        ListTag numbers = new ListTag();
        numbers.add(IntTag.valueOf(7));
        players.getCompoundOrEmpty(PLAYER.toString()).put("lifetimeCategories", numbers);
        ListTag unknown = new ListTag();
        unknown.add(StringTag.valueOf("not-a-category"));
        players.getCompoundOrEmpty(OTHER.toString()).put("lifetimeCategories", unknown);

        CensusData loaded = CensusData.CODEC.parse(OPS, root).getOrThrow();
        assertTrue(loaded.playerOrNull(PLAYER).lifetimeCategories.contains("fall"));
        assertTrue(loaded.playerOrNull(OTHER).lifetimeCategories.contains("lava"));
    }

    @Test
    void missingBaselineFieldsDecodeToMinusOne() {
        CompoundTag player = new CompoundTag();
        player.put("deaths", new ListTag());
        CompoundTag players = new CompoundTag();
        players.put(PLAYER.toString(), player);
        CompoundTag root = new CompoundTag();
        root.put("players", players);

        CensusData loaded = CensusData.CODEC.parse(OPS, root).getOrThrow();
        assertEquals(-1, loaded.player(PLAYER).baselinePlayTicks);
        assertEquals(-1, loaded.player(PLAYER).lastDeathPlayTicks);
    }

    @Test
    void epochZeroAndNegativeSurviveCodecRoundTrip() {
        CensusData data = new CensusData();
        data.recordDeath(death(PLAYER, 0L, "fall"), 100);
        Tag encoded = CensusData.CODEC.encodeStart(OPS, data).getOrThrow();
        CensusData loaded = CensusData.CODEC.parse(OPS, encoded).getOrThrow();
        assertEquals(1, loaded.player(PLAYER).deaths.size());
        assertEquals(0L, loaded.player(PLAYER).deaths.get(0).epochMillis());

        CompoundTag edited = (CompoundTag) encoded;
        edited.getCompoundOrEmpty("players").getCompoundOrEmpty(PLAYER.toString())
                .getListOrEmpty("deaths").getCompoundOrEmpty(0).putLong("epochMillis", -5L);
        CensusData negative = CensusData.CODEC.parse(OPS, edited).getOrThrow();
        assertEquals(1, negative.player(PLAYER).deaths.size());
        assertEquals(-5L, negative.player(PLAYER).deaths.get(0).epochMillis());
    }

    @Test
    void lifetimeRecordedSurvivesTrimAndPersistence() {
        CensusData data = new CensusData();
        for (int i = 0; i < 5; i++) {
            data.recordDeath(death(PLAYER, 100L + i, "fall"), 2);
        }
        assertEquals(5L, data.player(PLAYER).lifetimeRecorded);
        assertEquals(2, data.player(PLAYER).deaths.size());

        CompoundTag saved = (CompoundTag) CensusData.CODEC.encodeStart(OPS, data).getOrThrow();
        assertEquals(5L, saved.getCompoundOrEmpty("players")
                .getCompoundOrEmpty(PLAYER.toString()).getLongOr("lifetimeRecorded", -1L));
        CensusData loaded = CensusData.CODEC.parse(OPS, saved).getOrThrow();
        assertEquals(5L, loaded.player(PLAYER).lifetimeRecorded);
        assertEquals(2, loaded.player(PLAYER).deaths.size());

        // Old saves without the field derive it from retained records.
        CompoundTag old = saved.copy();
        old.getCompoundOrEmpty("players").getCompoundOrEmpty(PLAYER.toString()).remove("lifetimeRecorded");
        assertEquals(2L, CensusData.CODEC.parse(OPS, old).getOrThrow().player(PLAYER).lifetimeRecorded);
    }

    @Test
    void vanillaBaselineAfterTrimDeleteAndReImportDoesNotDoubleCount() {
        CensusData data = new CensusData();
        data.player(PLAYER).lastKnownName = "Alex";
        for (int i = 0; i < 200; i++) {
            data.recordDeath(death(PLAYER, 1000L + i, "fall"), 100);
        }
        assertTrue(data.removeDeath(PLAYER, 0));
        assertEquals(99, data.player(PLAYER).deaths.size());
        assertEquals(200L, data.player(PLAYER).lifetimeRecorded);

        Map<UUID, VanillaStats> stats = Map.of(PLAYER, new VanillaStats(205, Map.of()));
        VanillaImportResult first = data.importVanillaBaselines(stats, false);
        assertEquals(5, data.player(PLAYER).vanillaDeathsBaseline);
        assertEquals(5L, first.addedDeaths());
        assertEquals(205L, data.lifetimeDeaths());

        VanillaImportResult again = data.importVanillaBaselines(stats, false);
        assertEquals(0L, again.addedDeaths());
        assertEquals(5, data.player(PLAYER).vanillaDeathsBaseline);
        assertEquals(205L, data.lifetimeDeaths());

        CensusData loaded = CensusData.CODEC.parse(OPS,
                CensusData.CODEC.encodeStart(OPS, data).getOrThrow()).getOrThrow();
        assertEquals(200L, loaded.player(PLAYER).lifetimeRecorded);
        assertEquals(0L, loaded.importVanillaBaselines(stats, false).addedDeaths());
        assertEquals(205L, loaded.lifetimeDeaths());
    }

    @Test
    void derivedLifetimeCountersIgnoreSkippedDeathElements() {
        CensusData data = new CensusData();
        data.recordDeath(death(PLAYER, 100L, "fall"), 100);
        data.recordDeath(death(PLAYER, 200L, "lava"), 100);
        CompoundTag root = (CompoundTag) CensusData.CODEC.encodeStart(OPS, data).getOrThrow();
        CompoundTag playerTag = root.getCompoundOrEmpty("players").getCompoundOrEmpty(PLAYER.toString());
        ListTag deaths = new ListTag();
        deaths.add(StringTag.valueOf("bad death"));
        deaths.add(playerTag.getListOrEmpty("deaths").getCompoundOrEmpty(1).copy());
        playerTag.put("deaths", deaths);
        playerTag.remove("lifetimeRecorded");
        root.remove("lifetimeDeaths");

        CensusData loaded = CensusData.CODEC.parse(OPS, root).getOrThrow();
        assertEquals(1, loaded.playerOrNull(PLAYER).deaths.size());
        assertEquals(1L, loaded.playerOrNull(PLAYER).lifetimeRecorded);
        assertEquals(1L, loaded.storedLifetimeDeaths());
    }
}
