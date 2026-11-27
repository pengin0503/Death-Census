package com.pengin0503.deathcensus;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Storage hardening regressions (review B1, S5, S6, S1, M1). */
class StorageHardeningTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static DeathEntry death(UUID uuid, long epoch, String category) {
        return new DeathEntry(uuid.toString(), "Alex", epoch, 3L, 500, 400,
                "minecraft:overworld", 1.0, 64.0, 2.0, category, "minecraft:" + category,
                "Alex died", "", "", "survival", 5, "", "", 0);
    }

    /** 1.21.1 ListTag enforces one element type; emulate a hand-corrupted mixed list. */
    private static ListTag mixedList(Tag first, Tag second) throws Exception {
        Constructor<ListTag> ctor = ListTag.class.getDeclaredConstructor(List.class, byte.class);
        ctor.setAccessible(true);
        return ctor.newInstance(List.of(first, second), first.getId());
    }

    @Test
    void wrongTypedPlayersContainerPreservesRawTagOnSave() {
        CompoundTag raw = new CompoundTag();
        raw.putString("players", "not a compound");
        raw.putLong("lifetimeDeaths", 42L);
        raw.putString("marker", "keep me");

        CensusData loaded = CensusData.load(raw, null);
        assertTrue(loaded.isEmpty());
        CompoundTag saved = loaded.save(new CompoundTag(), null);
        assertEquals(raw, saved);
        assertEquals("not a compound", saved.getString("players"));
        assertEquals("keep me", saved.getString("marker"));
        // A second save must not "repair" the corruption either.
        assertEquals(raw, CensusData.load(saved, null).save(new CompoundTag(), null));
    }

    @Test
    void absentPlayersContainerLoadsEmptyAndDoesNotBlockSave() {
        CensusData loaded = CensusData.load(new CompoundTag(), null);
        assertTrue(loaded.isEmpty());
        CompoundTag saved = loaded.save(new CompoundTag(), null);
        assertTrue(saved.contains("players", Tag.TAG_COMPOUND));
        assertTrue(saved.getCompound("players").isEmpty());
    }

    @Test
    void wrongTypedPlayerValuesArePreservedWhileValidSiblingLoads() {
        CensusData data = new CensusData();
        data.recordDeath(death(PLAYER, 100L, "fall"), 100);
        CompoundTag root = data.save(new CompoundTag(), null);
        CompoundTag players = root.getCompound("players");
        players.put(OTHER.toString(), StringTag.valueOf("corrupt player"));
        players.put("not-a-uuid", StringTag.valueOf("corrupt key"));

        CensusData loaded = CensusData.load(root, null);
        assertEquals(1, loaded.playerOrNull(PLAYER).deaths.size());
        assertNull(loaded.playerOrNull(OTHER));
        assertEquals(1, loaded.allPlayers().size(), "invalid UUID must not load as a player");

        CompoundTag saved = loaded.save(new CompoundTag(), null);
        assertEquals("corrupt player", saved.getCompound("players").getString(OTHER.toString()));
        assertEquals("corrupt key", saved.getCompound("players").getString("not-a-uuid"));
        assertEquals(1, saved.getCompound("players").getCompound(PLAYER.toString())
                .getList("deaths", Tag.TAG_COMPOUND).size());
    }

    @Test
    void wrongTypedDeathElementsDoNotDropValidCompoundSiblings() throws Exception {
        CensusData data = new CensusData();
        data.recordDeath(death(PLAYER, 100L, "fall"), 100);
        CompoundTag root = data.save(new CompoundTag(), null);
        CompoundTag playerTag = root.getCompound("players").getCompound(PLAYER.toString());
        CompoundTag validDeath = playerTag.getList("deaths", Tag.TAG_COMPOUND).getCompound(0).copy();

        // Declared type string, but the list still holds a compound record;
        // the typed getList would discard the whole list.
        playerTag.put("deaths", mixedList(StringTag.valueOf("bad death"), validDeath));

        CensusData loaded = CensusData.load(root, null);
        assertEquals(1, loaded.playerOrNull(PLAYER).deaths.size(), "valid sibling must survive");
        assertEquals(100L, loaded.playerOrNull(PLAYER).deaths.get(0).epochMillis());
        assertEquals(1, loaded.save(new CompoundTag(), null).getCompound("players")
                .getCompound(PLAYER.toString()).getList("deaths", Tag.TAG_COMPOUND).size());
    }

    @Test
    void lifetimeCategoriesWithNoUsableStringsFallsBackToDeaths() {
        CensusData data = new CensusData();
        data.recordDeath(death(PLAYER, 100L, "fall"), 100);
        data.recordDeath(death(OTHER, 200L, "lava"), 100);
        CompoundTag root = data.save(new CompoundTag(), null);
        CompoundTag players = root.getCompound("players");

        ListTag numbers = new ListTag();
        numbers.add(IntTag.valueOf(7));
        players.getCompound(PLAYER.toString()).put("lifetimeCategories", numbers);
        ListTag unknown = new ListTag();
        unknown.add(StringTag.valueOf("not-a-category"));
        players.getCompound(OTHER.toString()).put("lifetimeCategories", unknown);

        CensusData loaded = CensusData.load(root, null);
        assertTrue(loaded.playerOrNull(PLAYER).lifetimeCategories.contains("fall"));
        assertTrue(loaded.playerOrNull(OTHER).lifetimeCategories.contains("lava"));
    }

    @Test
    void lifetimeRecordedSurvivesTrimAndPersistence() {
        CensusData data = new CensusData();
        for (int i = 0; i < 5; i++) {
            data.recordDeath(death(PLAYER, 100L + i, "fall"), 2);
        }
        assertEquals(5L, data.player(PLAYER).lifetimeRecorded);
        assertEquals(2, data.player(PLAYER).deaths.size());

        CompoundTag saved = data.save(new CompoundTag(), null);
        assertEquals(5L, saved.getCompound("players").getCompound(PLAYER.toString()).getLong("lifetimeRecorded"));
        CensusData loaded = CensusData.load(saved, null);
        assertEquals(5L, loaded.player(PLAYER).lifetimeRecorded);
        assertEquals(2, loaded.player(PLAYER).deaths.size());

        // Old saves without the field derive it from retained records.
        CompoundTag old = saved.copy();
        old.getCompound("players").getCompound(PLAYER.toString()).remove("lifetimeRecorded");
        assertEquals(2L, CensusData.load(old, null).player(PLAYER).lifetimeRecorded);
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

        CensusData loaded = CensusData.load(data.save(new CompoundTag(), null), null);
        assertEquals(200L, loaded.player(PLAYER).lifetimeRecorded);
        assertEquals(0L, loaded.importVanillaBaselines(stats, false).addedDeaths());
        assertEquals(205L, loaded.lifetimeDeaths());
    }

    @Test
    void derivedLifetimeCountersIgnoreSkippedDeathElements() throws Exception {
        CensusData data = new CensusData();
        data.recordDeath(death(PLAYER, 100L, "fall"), 100);
        data.recordDeath(death(PLAYER, 200L, "lava"), 100);
        CompoundTag root = data.save(new CompoundTag(), null);
        CompoundTag playerTag = root.getCompound("players").getCompound(PLAYER.toString());
        playerTag.put("deaths", mixedList(StringTag.valueOf("bad death"),
                playerTag.getList("deaths", Tag.TAG_COMPOUND).getCompound(1).copy()));
        playerTag.remove("lifetimeRecorded");
        root.remove("lifetimeDeaths");

        CensusData loaded = CensusData.load(root, null);
        assertEquals(1, loaded.playerOrNull(PLAYER).deaths.size());
        assertEquals(1L, loaded.playerOrNull(PLAYER).lifetimeRecorded);
        assertEquals(1L, loaded.storedLifetimeDeaths());
    }
}
