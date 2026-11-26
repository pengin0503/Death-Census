package com.pengin0503.deathcensus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PR-1 merge gate for the 26.x codec: old saves/backups, malformed elements, round trips. */
class StorageMigrationTest {
    private static final UUID UUID_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static CompoundTag save(CensusData data) {
        return (CompoundTag) CensusData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
    }

    private static CensusData load(CompoundTag tag) {
        return CensusData.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
    }

    private static DeathEntry entry(long epoch, boolean additive) {
        DeathEntry old = new DeathEntry(UUID_A.toString(), "Alex", epoch, 3L, 500, 400,
                "minecraft:overworld", 10.0, 64.0, -20.0, "fall", "minecraft:fall",
                "Alex fell", "", "", "survival", 5, "excuse", "last words", 2);
        if (!additive) {
            return old;
        }
        return new DeathEntry(UUID_A.toString(), "Alex", epoch, 3L, 500, 400,
                "minecraft:overworld", 10.0, 64.0, -20.0, "fall", "minecraft:fall",
                "Alex fell", "", "", "survival", 5, "excuse", "last words", 2,
                List.of(new WitnessRef("00000000-0000-0000-0000-0000000000b2", "Bailey")),
                new DeathContext("minecraft:sword", List.of("minecraft:helmet"),
                        List.of("minecraft:speed:1"), "minecraft:plains", "clear"));
    }

    private static NearDeathEvent nearDeath(long epoch) {
        return new NearDeathEvent(UUID_A.toString(), "Alex", epoch, "minecraft:overworld",
                1.0, 2.0, 3.0, "fall", "minecraft:fall", NearDeathEvent.Kind.TOTEM);
    }

    private static ChronicleEvent chronicle(long epoch) {
        return new ChronicleEvent(epoch, 3L, ChronicleEvent.Kind.SERVER_FIRST,
                UUID_A.toString(), "Alex", "minecraft:overworld", "fall", 1, true);
    }

    private static DeathEntry coreOf(DeathEntry e) {
        return new DeathEntry(e.playerUuid(), e.playerName(), e.epochMillis(), e.gameDay(),
                e.playTicks(), e.survivalTicks(), e.dimension(), e.x(), e.y(), e.z(),
                e.causeCategory(), e.causeId(), e.deathMessage(), e.killerType(),
                e.killerName(), e.gameMode(), e.experienceLevel(), e.excuse(),
                e.lastWords(), e.fCount());
    }

    private static CensusData dataWithEverything() {
        CensusData data = new CensusData();
        data.player(UUID_A).lastKnownName = "Alex";
        data.recordDeath(entry(1000L, true), 10000);
        data.player(UUID_A).nearDeaths.add(nearDeath(2000L));
        data.chronicleEvents.add(chronicle(1000L));
        return data;
    }

    private static CompoundTag playerTag(CompoundTag root) {
        return root.getCompoundOrEmpty("players").getCompoundOrEmpty(UUID_A.toString());
    }

    @Test
    void oldCodecSaveLoadsWithEmptyPlaceholdersAndUnchangedRecords() {
        CensusData data = dataWithEverything();
        CompoundTag saved = save(data);
        CompoundTag death = playerTag(saved).getListOrEmpty("deaths").getCompoundOrEmpty(0);
        death.remove("witnesses");
        death.remove("context");
        playerTag(saved).remove("nearDeaths");
        saved.remove("chronicleEvents");

        CensusData loaded = load(saved);
        CensusData.PlayerCensus pc = loaded.playerOrNull(UUID_A);
        assertNotNull(pc);
        assertEquals(1, pc.deaths.size());
        assertEquals(coreOf(entry(1000L, false)), coreOf(pc.deaths.get(0)));
        assertTrue(pc.deaths.get(0).witnesses().isEmpty());
        assertEquals(DeathContext.EMPTY, pc.deaths.get(0).context());
        assertTrue(pc.nearDeaths.isEmpty());
        assertTrue(loaded.chronicleEvents.isEmpty());

        CensusData again = load(save(loaded));
        assertTrue(again.playerOrNull(UUID_A).nearDeaths.isEmpty());
        assertTrue(again.chronicleEvents.isEmpty());
    }

    @Test
    void codecRoundTripPreservesAllNewFields() {
        CensusData loaded = load(save(dataWithEverything()));
        CensusData.PlayerCensus pc = loaded.playerOrNull(UUID_A);
        assertNotNull(pc);
        assertEquals(entry(1000L, true), pc.deaths.get(0));
        assertEquals(List.of(nearDeath(2000L)), pc.nearDeaths);
        assertEquals(List.of(chronicle(1000L)), loaded.chronicleEvents);

        CensusData twice = load(save(loaded));
        assertEquals(entry(1000L, true), twice.playerOrNull(UUID_A).deaths.get(0));
        assertEquals(List.of(nearDeath(2000L)), twice.playerOrNull(UUID_A).nearDeaths);
        assertEquals(List.of(chronicle(1000L)), twice.chronicleEvents());
    }

    @Test
    void codecCorruptAggregateElementsAreSkippedWithoutDroppingTheDeath() {
        CensusData data = new CensusData();
        data.player(UUID_A).lastKnownName = "Alex";
        data.recordDeath(entry(1000L, false), 10000);
        CompoundTag saved = save(data);

        CompoundTag death = playerTag(saved).getListOrEmpty("deaths").getCompoundOrEmpty(0);
        ListTag witnesses = new ListTag();
        witnesses.add(new CompoundTag()); // no uuid/name -> skipped
        CompoundTag goodWitness = new CompoundTag();
        goodWitness.putString("uuid", "w");
        goodWitness.putString("nameAtDeath", "Bailey");
        witnesses.add(goodWitness);
        death.put("witnesses", witnesses);
        death.putString("context", "not a compound"); // defaults to EMPTY

        ListTag nearDeaths = new ListTag();
        nearDeaths.add(new CompoundTag()); // no epoch -> skipped
        nearDeaths.add(nearDeathTag(nearDeath(2000L)));
        playerTag(saved).put("nearDeaths", nearDeaths);

        ListTag chronicle = new ListTag();
        chronicle.add(new CompoundTag()); // no epoch -> skipped
        chronicle.add(chronicleTag(chronicle(1000L)));
        saved.put("chronicleEvents", chronicle);

        CensusData loaded = load(saved);
        CensusData.PlayerCensus pc = loaded.playerOrNull(UUID_A);
        assertNotNull(pc);
        assertEquals(1, pc.deaths.size());
        assertEquals(coreOf(entry(1000L, false)), coreOf(pc.deaths.get(0)));
        assertEquals(List.of(new WitnessRef("w", "Bailey")), pc.deaths.get(0).witnesses());
        assertEquals(DeathContext.EMPTY, pc.deaths.get(0).context());
        assertEquals(List.of(nearDeath(2000L)), pc.nearDeaths);
        assertEquals(List.of(chronicle(1000L)), loaded.chronicleEvents);
    }

    private static CompoundTag nearDeathTag(NearDeathEvent e) {
        CompoundTag t = new CompoundTag();
        t.putString("uuid", e.uuid());
        t.putString("name", e.name());
        t.putLong("epochMillis", e.epochMillis());
        t.putString("dimension", e.dimension());
        t.putDouble("x", e.x());
        t.putDouble("y", e.y());
        t.putDouble("z", e.z());
        t.putString("causeCategory", e.causeCategory());
        t.putString("causeId", e.causeId());
        t.putString("kind", e.kind().name());
        return t;
    }

    private static CompoundTag chronicleTag(ChronicleEvent e) {
        CompoundTag t = new CompoundTag();
        t.putLong("epochMillis", e.epochMillis());
        t.putLong("gameDay", e.gameDay());
        t.putString("kind", e.kind().name());
        t.putString("uuid", e.uuid());
        t.putString("name", e.name());
        t.putString("dimension", e.dimension());
        t.putString("causeCategory", e.causeCategory());
        t.putInt("count", e.count());
        t.putBoolean("reconstructed", e.reconstructed());
        return t;
    }

    private static JsonObject backupJson() {
        PlayerDeaths pd = new PlayerDeaths(UUID_A.toString(), "Alex", false, false, false, false,
                List.of(entry(1000L, true)), 0, Map.of(), List.of(nearDeath(2000L)));
        return JsonParser.parseString(ExportRenderers.toBackupJson(List.of(pd), 3000L,
                List.of(chronicle(1000L)))).getAsJsonObject();
    }

    @Test
    void oldBackupJsonLoadsWithEmptyPlaceholders() {
        JsonObject root = backupJson();
        root.remove("chronicleEvents");
        JsonObject player = root.getAsJsonArray("players").get(0).getAsJsonObject();
        player.remove("nearDeaths");
        JsonObject death = player.getAsJsonArray("deaths").get(0).getAsJsonObject();
        death.remove("witnesses");
        death.remove("context");

        List<ChronicleEvent> chronicle = new ArrayList<>();
        List<PlayerDeaths> players = CensusExport.parseBackupJson(root.toString(), chronicle);
        assertNotNull(players);
        assertEquals(1, players.size());
        DeathEntry loaded = players.get(0).deaths().get(0);
        assertEquals(coreOf(entry(1000L, false)), coreOf(loaded));
        assertTrue(loaded.witnesses().isEmpty());
        assertEquals(DeathContext.EMPTY, loaded.context());
        assertTrue(players.get(0).nearDeaths().isEmpty());
        assertTrue(chronicle.isEmpty());
    }

    @Test
    void backupJsonRoundTripAndCorruptElementsPreserveValidSiblings() {
        List<ChronicleEvent> chronicle = new ArrayList<>();
        List<PlayerDeaths> players = CensusExport.parseBackupJson(backupJson().toString(), chronicle);
        assertNotNull(players);
        assertEquals(entry(1000L, true), players.get(0).deaths().get(0));
        assertEquals(List.of(nearDeath(2000L)), players.get(0).nearDeaths());
        assertEquals(List.of(chronicle(1000L)), chronicle);

        JsonObject root = backupJson();
        JsonArray badChronicle = new JsonArray();
        badChronicle.add(new JsonPrimitive("not an event"));
        badChronicle.add(root.getAsJsonArray("chronicleEvents").get(0));
        root.add("chronicleEvents", badChronicle);
        JsonObject player = root.getAsJsonArray("players").get(0).getAsJsonObject();
        JsonArray badNear = new JsonArray();
        badNear.add(new JsonPrimitive("not an event"));
        badNear.add(player.getAsJsonArray("nearDeaths").get(0));
        player.add("nearDeaths", badNear);
        JsonObject death = player.getAsJsonArray("deaths").get(0).getAsJsonObject();
        JsonArray badWitnesses = new JsonArray();
        badWitnesses.add(new JsonPrimitive(7));
        badWitnesses.add(death.getAsJsonArray("witnesses").get(0));
        death.add("witnesses", badWitnesses);
        death.add("context", new JsonPrimitive("not a context"));

        List<ChronicleEvent> recovered = new ArrayList<>();
        List<PlayerDeaths> recoveredPlayers = CensusExport.parseBackupJson(root.toString(), recovered);
        assertNotNull(recoveredPlayers);
        assertEquals(1, recoveredPlayers.size());
        DeathEntry recoveredDeath = recoveredPlayers.get(0).deaths().get(0);
        assertEquals(coreOf(entry(1000L, false)), coreOf(recoveredDeath));
        assertEquals(List.of(new WitnessRef("00000000-0000-0000-0000-0000000000b2", "Bailey")),
                recoveredDeath.witnesses());
        assertEquals(DeathContext.EMPTY, recoveredDeath.context());
        assertEquals(List.of(nearDeath(2000L)), recoveredPlayers.get(0).nearDeaths());
        assertEquals(List.of(chronicle(1000L)), recovered);
        assertFalse(recoveredDeath.playerUuid().isEmpty());
    }
}
