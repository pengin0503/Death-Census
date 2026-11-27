package com.pengin0503.deathcensus;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NBT storage hardening regressions (review B1, S5, S6, M1). */
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

        // Even if something is recorded in memory, the corrupt raw tag wins:
        // no autosave may replace it with a fresh, empty players map.
        loaded.recordDeath(death(PLAYER, 100L, "fall"), 100);
        assertEquals(raw, loaded.save(new CompoundTag(), null));
        // A second load/save cycle must not "repair" the corruption either.
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
        data.player(PLAYER).optOutPublic = true;
        CompoundTag root = data.save(new CompoundTag(), null);
        CompoundTag players = root.getCompound("players");
        players.put(OTHER.toString(), StringTag.valueOf("corrupt player"));
        players.put("not-a-uuid", StringTag.valueOf("corrupt key"));

        CensusData loaded = CensusData.load(root, null);
        assertEquals(1, loaded.playerOrNull(PLAYER).deaths.size());
        assertTrue(loaded.playerOrNull(PLAYER).optOutPublic);
        assertNull(loaded.playerOrNull(OTHER));
        assertEquals(1, loaded.allPlayers().size(), "invalid UUID must not load as a player");

        CompoundTag saved = loaded.save(new CompoundTag(), null);
        assertEquals("corrupt player", saved.getCompound("players").getString(OTHER.toString()));
        assertEquals("corrupt key", saved.getCompound("players").getString("not-a-uuid"));
        assertEquals(1, saved.getCompound("players").getCompound(PLAYER.toString())
                .getList("deaths", Tag.TAG_COMPOUND).size());

        // A live player recreated under a preserved key wins over the raw value.
        CensusData recovered = CensusData.load(root, null);
        recovered.player(OTHER).lastKnownName = "Recovered";
        CompoundTag withLive = recovered.save(new CompoundTag(), null);
        assertTrue(withLive.getCompound("players").contains(OTHER.toString(), Tag.TAG_COMPOUND));
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
    void wrongTypedDeathListKeepsThePlayerAndDoesNotSilentlyWipeIt() {
        CensusData data = new CensusData();
        data.recordDeath(death(PLAYER, 100L, "fall"), 100);
        data.player(PLAYER).optOutPublic = true;
        CompoundTag root = data.save(new CompoundTag(), null);
        root.getCompound("players").getCompound(PLAYER.toString()).putString("deaths", "bad list");

        CensusData loaded = CensusData.load(root, null);
        assertTrue(loaded.playerOrNull(PLAYER) != null, "the player must not be dropped with its list");
        assertTrue(loaded.playerOrNull(PLAYER).deaths.isEmpty());
        assertTrue(loaded.playerOrNull(PLAYER).optOutPublic);
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
}
