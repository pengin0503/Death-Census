package com.pengin0503.deathcensus;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CensusDataTest {
    private static DeathEntry entry(UUID uuid, String name, long epoch, int play, int surv) {
        return new DeathEntry(uuid.toString(), name, epoch, 3L, play, surv,
                "minecraft:overworld", 10.0, 64.0, -20.0, "fall", "minecraft:fall",
                name + " fell from a high place", "", "", "survival", 5, "", "", 0);
    }

    private static DeathEntry entryWithStory(UUID uuid, String name, long epoch) {
        return new DeathEntry(uuid.toString(), name, epoch, 3L, 500, 400,
                "minecraft:overworld", 10.0, 64.0, -20.0, "fall", "minecraft:fall",
                name + " fell from a high place", "", "", "survival", 5,
                "It wasn't my fault", "See you on the other side", 4);
    }

    @Test
    void nbtRoundTripKeepsRecordsAndFlags() {
        UUID uuid = UUID.randomUUID();
        CensusData data = new CensusData();
        data.player(uuid).baselinePlayTicks = 100;
        data.recordDeath(entryWithStory(uuid, "Steve", 1700000000000L), 10000);
        data.recordDeath(entry(uuid, "Steve", 1700000100000L, 9000, 4000), 10000);
        data.player(uuid).optOutPublic = true;
        data.player(uuid).hideCoordinates = true;
        data.player(uuid).hidePvpOpponent = true;
        data.player(uuid).discordOptOut = true;
        data.setLastDigestEpochMillis(123456789L);

        CompoundTag saved = data.save(new CompoundTag(), null);
        CensusData loaded = CensusData.load(saved, null);

        CensusData.PlayerCensus pc = loaded.playerOrNull(uuid);
        assertTrue(pc != null);
        assertEquals(2, pc.deaths.size());
        assertEquals("fall", pc.deaths.get(0).causeCategory());
        assertEquals(400, pc.deaths.get(0).survivalTicks());
        assertEquals("It wasn't my fault", pc.deaths.get(0).excuse());
        assertEquals("See you on the other side", pc.deaths.get(0).lastWords());
        assertEquals(4, pc.deaths.get(0).fCount());
        assertEquals(9000, pc.lastDeathPlayTicks);
        assertEquals(100, pc.baselinePlayTicks);
        assertTrue(pc.optOutPublic);
        assertTrue(pc.hideCoordinates);
        assertTrue(pc.hidePvpOpponent);
        assertTrue(pc.discordOptOut);
        assertEquals("Steve", pc.lastKnownName);
        assertEquals(123456789L, loaded.lastDigestEpochMillis());
    }

    @Test
    void nbtOldSaveWithoutGranularFieldsLoadsWithDefaultsAndRoundTrips() {
        UUID uuid = UUID.randomUUID();
        CensusData data = new CensusData();
        data.player(uuid).optOutPublic = true;
        data.recordDeath(entry(uuid, "Steve", 1700000000000L, 300, 300), 10000);
        CompoundTag saved = data.save(new CompoundTag(), null);
        CompoundTag playerTag = saved.getCompound("players").getCompound(uuid.toString());
        // Simulate a 1.2.0 save: only the old opt-out field exists.
        playerTag.remove("hideCoordinates");
        playerTag.remove("hidePvpOpponent");
        playerTag.remove("discordOptOut");

        CensusData loaded = CensusData.load(saved, null);
        CensusData.PlayerCensus pc = loaded.playerOrNull(uuid);
        assertTrue(pc != null);
        assertTrue(pc.optOutPublic);
        assertFalse(pc.hideCoordinates);
        assertFalse(pc.hidePvpOpponent);
        assertFalse(pc.discordOptOut);
        assertEquals(1, pc.deaths.size());

        // New fields are written going forward and survive another save/load.
        pc.hideCoordinates = true;
        pc.discordOptOut = true;
        CensusData reloaded = CensusData.load(loaded.save(new CompoundTag(), null), null);
        assertTrue(reloaded.playerOrNull(uuid).hideCoordinates);
        assertFalse(reloaded.playerOrNull(uuid).hidePvpOpponent);
        assertTrue(reloaded.playerOrNull(uuid).discordOptOut);
    }

    @Test
    void nbtWrongTypedGranularFieldsDoNotFailTheLoad() {
        UUID good = UUID.randomUUID();
        UUID bad = UUID.randomUUID();
        CensusData data = new CensusData();
        data.recordDeath(entry(good, "Good", 1700000000000L, 300, 300), 10000);
        CompoundTag saved = data.save(new CompoundTag(), null);
        CompoundTag players = saved.getCompound("players");
        CompoundTag badPlayer = new CompoundTag();
        badPlayer.putString("lastKnownName", "Bad");
        badPlayer.putString("hideCoordinates", "not a boolean");
        badPlayer.putString("hidePvpOpponent", "not a boolean");
        badPlayer.putString("discordOptOut", "not a boolean");
        players.put(bad.toString(), badPlayer);

        CensusData loaded = CensusData.load(saved, null);
        CensusData.PlayerCensus valid = loaded.playerOrNull(good);
        assertTrue(valid != null);
        assertEquals(1, valid.deaths.size());
        CensusData.PlayerCensus malformed = loaded.playerOrNull(bad);
        assertTrue(malformed != null);
        assertFalse(malformed.hideCoordinates);
        assertFalse(malformed.hidePvpOpponent);
        assertFalse(malformed.discordOptOut);
    }

    private static DeathEntry entryWithCategory(UUID uuid, String name, long epoch, String category) {
        return new DeathEntry(uuid.toString(), name, epoch, 3L, 500, 400,
                "minecraft:overworld", 10.0, 64.0, -20.0, category, "minecraft:" + category,
                name + " died (" + category + ")", "", "", "survival", 5, "", "", 0);
    }

    @Test
    void nbtPersistsLifetimeCounterAndCategories() {
        UUID uuid = UUID.randomUUID();
        CensusData data = new CensusData();
        data.recordDeath(entryWithCategory(uuid, "Alex", 100L, "fall"), 1);
        data.recordDeath(entryWithCategory(uuid, "Alex", 200L, "lava"), 1);

        CensusData loaded = CensusData.load(data.save(new CompoundTag(), null), null);
        assertEquals(2L, loaded.lifetimeDeaths());
        assertEquals(2, loaded.playerOrNull(uuid).lifetimeCategories.size());
        assertTrue(loaded.playerOrNull(uuid).lifetimeCategories.contains("fall"));
        assertTrue(loaded.playerOrNull(uuid).lifetimeCategories.contains("lava"));
    }

    @Test
    void loadingWithoutLifetimeFieldsInitializesThemFromRetainedDeaths() {
        UUID uuid = UUID.randomUUID();
        CensusData data = new CensusData();
        data.recordDeath(entryWithCategory(uuid, "Alex", 100L, "fall"), 100);
        data.recordDeath(entryWithCategory(uuid, "Alex", 200L, "lava"), 100);

        CompoundTag saved = data.save(new CompoundTag(), null);
        saved.remove("lifetimeDeaths");
        saved.getCompound("players").getCompound(uuid.toString()).remove("lifetimeCategories");

        CensusData loaded = CensusData.load(saved, null);
        assertEquals(2L, loaded.lifetimeDeaths());
        assertEquals(2, loaded.playerOrNull(uuid).lifetimeCategories.size());
    }

    @Test
    void lifetimeTotalsAndCategoriesSurviveTrimming() {
        UUID uuid = UUID.randomUUID();
        CensusData data = new CensusData();
        data.recordDeath(entryWithCategory(uuid, "Alex", 100L, "fall"), 1);
        data.recordDeath(entryWithCategory(uuid, "Alex", 200L, "lava"), 1);
        data.recordDeath(entryWithCategory(uuid, "Alex", 300L, "fall"), 1);

        assertEquals(1, data.playerOrNull(uuid).deaths.size());
        assertEquals("fall", data.playerOrNull(uuid).deaths.get(0).causeCategory());
        assertEquals(3L, data.lifetimeDeaths());
        assertTrue(data.playerOrNull(uuid).lifetimeCategories.contains("lava"));
    }

    @Test
    void nbtDefaultsWhenStoryFieldsAbsent() {
        UUID uuid = UUID.randomUUID();
        CensusData data = new CensusData();
        data.recordDeath(entryWithStory(uuid, "Steve", 1700000000000L), 10000);
        CompoundTag saved = data.save(new CompoundTag(), null);
        CompoundTag deathTag = saved.getCompound("players").getCompound(uuid.toString())
                .getList("deaths", Tag.TAG_COMPOUND).getCompound(0);
        deathTag.remove("excuse");
        deathTag.remove("lastwords");
        deathTag.remove("fcount");

        CensusData loaded = CensusData.load(saved, null);
        DeathEntry e = loaded.playerOrNull(uuid).deaths.get(0);
        assertEquals("", e.excuse());
        assertEquals("", e.lastWords());
        assertEquals(0, e.fCount());
    }

    @Test
    void trimmingDropsOldestBeyondCap() {
        UUID uuid = UUID.randomUUID();
        CensusData data = new CensusData();
        for (int i = 0; i < 5; i++) {
            data.recordDeath(entry(uuid, "Alex", 1000L + i, 100 * (i + 1), 100), 3);
        }
        List<DeathEntry> deaths = data.playerOrNull(uuid).deaths;
        assertEquals(3, deaths.size());
        assertEquals(1002L, deaths.get(0).epochMillis());
    }

    @Test
    void snapshotAndReplaceAllRoundTrip() {
        UUID uuid = UUID.randomUUID();
        CensusData data = new CensusData();
        data.recordDeath(entry(uuid, "Steve", 42L, 700, 700), 10000);
        List<PlayerDeaths> snapshot = data.snapshot();
        assertEquals(1, snapshot.size());
        assertFalse(snapshot.get(0).optOutPublic());

        CensusData other = new CensusData();
        other.replaceAll(snapshot);
        assertEquals(1, other.playerOrNull(uuid).deaths.size());
        assertEquals(700, other.playerOrNull(uuid).lastDeathPlayTicks);
    }

    @Test
    void removeDeathDropsChronologicalEntryAndUpdatesLastDeath() {
        UUID uuid = UUID.randomUUID();
        CensusData data = new CensusData();
        data.recordDeath(entry(uuid, "Steve", 100L, 100, 50), 10000);
        data.recordDeath(entry(uuid, "Steve", 200L, 200, 60), 10000);
        data.recordDeath(entry(uuid, "Steve", 300L, 300, 70), 10000);

        assertTrue(data.removeDeath(uuid, 1));
        List<DeathEntry> deaths = data.playerOrNull(uuid).deaths;
        assertEquals(2, deaths.size());
        assertEquals(100L, deaths.get(0).epochMillis());
        assertEquals(300L, deaths.get(1).epochMillis());
        assertEquals(300, data.playerOrNull(uuid).lastDeathPlayTicks);

        assertFalse(data.removeDeath(uuid, 2));
        assertFalse(data.removeDeath(uuid, -1));
        assertFalse(data.removeDeath(UUID.randomUUID(), 0));

        assertTrue(data.removeDeath(uuid, 1));
        assertTrue(data.removeDeath(uuid, 0));
        assertTrue(data.playerOrNull(uuid).deaths.isEmpty());
        assertEquals(-1, data.playerOrNull(uuid).lastDeathPlayTicks);
    }

    @Test
    void removePlayerDropsWholeState() {
        UUID uuid = UUID.randomUUID();
        CensusData data = new CensusData();
        data.recordDeath(entry(uuid, "Steve", 100L, 100, 50), 10000);
        data.player(uuid).optOutPublic = true;
        assertTrue(data.removePlayer(uuid));
        assertTrue(data.playerOrNull(uuid) == null);
        assertFalse(data.removePlayer(uuid));
    }

    @Test
    void backupJsonRoundTrip() {
        UUID uuid = UUID.randomUUID();
        List<PlayerDeaths> players = List.of(new PlayerDeaths(uuid.toString(), "Steve", true,
                List.of(entry(uuid, "Steve", 99L, 300, 300))));
        String json = ExportRenderers.toJson(players, 123L);
        List<PlayerDeaths> parsed = CensusExport.parseBackupJson(json);
        assertTrue(parsed != null);
        assertEquals(1, parsed.size());
        assertEquals(1, parsed.get(0).deaths().size());
        assertEquals("minecraft:fall", parsed.get(0).deaths().get(0).causeId());
        assertTrue(parsed.get(0).optOutPublic());
    }
}
