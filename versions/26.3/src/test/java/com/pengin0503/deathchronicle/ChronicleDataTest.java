package com.pengin0503.deathchronicle;

import com.mojang.serialization.DynamicOps;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChronicleDataTest {
    private static DeathEntry entry(UUID uuid, String name, long epoch, int play, int surv) {
        return new DeathEntry(uuid.toString(), name, epoch, 3L, play, surv,
                "minecraft:overworld", 10.0, 64.0, -20.0, "fall", "minecraft:fall",
                name + " fell from a high place", "", "", "survival", 5);
    }

    @Test
    void codecRoundTripKeepsRecordsAndFlags() {
        UUID uuid = UUID.randomUUID();
        ChronicleData data = new ChronicleData();
        data.player(uuid).baselinePlayTicks = 100;
        data.recordDeath(entry(uuid, "Steve", 1700000000000L, 5000, 4900), 10000);
        data.recordDeath(entry(uuid, "Steve", 1700000100000L, 9000, 4000), 10000);
        data.player(uuid).optOutPublic = true;
        data.setLastDigestEpochMillis(123456789L);

        DynamicOps<Tag> ops = NbtOps.INSTANCE;
        Tag encoded = ChronicleData.CODEC.encodeStart(ops, data).getOrThrow();
        ChronicleData loaded = ChronicleData.CODEC.parse(ops, encoded).getOrThrow();

        ChronicleData.PlayerChronicle pc = loaded.playerOrNull(uuid);
        assertTrue(pc != null);
        assertEquals(2, pc.deaths.size());
        assertEquals("fall", pc.deaths.get(0).causeCategory());
        assertEquals(4900, pc.deaths.get(0).survivalTicks());
        assertEquals(9000, pc.lastDeathPlayTicks);
        assertEquals(100, pc.baselinePlayTicks);
        assertTrue(pc.optOutPublic);
        assertEquals("Steve", pc.lastKnownName);
        assertEquals(123456789L, loaded.lastDigestEpochMillis());
    }

    @Test
    void trimmingDropsOldestBeyondCap() {
        UUID uuid = UUID.randomUUID();
        ChronicleData data = new ChronicleData();
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
        ChronicleData data = new ChronicleData();
        data.recordDeath(entry(uuid, "Steve", 42L, 700, 700), 10000);
        List<PlayerDeaths> snapshot = data.snapshot();
        assertEquals(1, snapshot.size());
        assertFalse(snapshot.get(0).optOutPublic());

        ChronicleData other = new ChronicleData();
        other.replaceAll(snapshot);
        assertEquals(1, other.playerOrNull(uuid).deaths.size());
        assertEquals(700, other.playerOrNull(uuid).lastDeathPlayTicks);
    }

    @Test
    void backupJsonRoundTrip() {
        UUID uuid = UUID.randomUUID();
        List<PlayerDeaths> players = List.of(new PlayerDeaths(uuid.toString(), "Steve", true,
                List.of(entry(uuid, "Steve", 99L, 300, 300))));
        String json = ExportRenderers.toJson(players, 123L);
        List<PlayerDeaths> parsed = ChronicleExport.parseBackupJson(json);
        assertTrue(parsed != null);
        assertEquals(1, parsed.size());
        assertEquals(1, parsed.get(0).deaths().size());
        assertEquals("minecraft:fall", parsed.get(0).deaths().get(0).causeId());
        assertTrue(parsed.get(0).optOutPublic());
    }
}
