package com.pengin0503.deathcensus;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.serialization.DynamicOps;
import com.pengin0503.deathcensus.ui.CompareView;
import com.pengin0503.deathcensus.ui.DialogUi;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runtime and wiring regressions for the 26.x review fixes (S1-S6, M1-M14). */
class ReviewFixesTest {
    private static final UUID UUID_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String WEBHOOK = "https://discord.com/api/webhooks/111/aaa";

    private static String source(String file) throws IOException {
        String dir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(dir, "deathcensus.mainSourceDir system property is not set");
        return Files.readString(Path.of(dir, "com", "pengin0503", "deathcensus", file));
    }

    private static String section(String text, String startMarker, String endMarker) {
        int start = text.indexOf(startMarker);
        assertTrue(start >= 0, "missing " + startMarker);
        int end = text.indexOf(endMarker, start + startMarker.length());
        assertTrue(end > start, "missing " + endMarker);
        return text.substring(start, end);
    }

    private static DeathEntry death(UUID uuid, long epoch, String category) {
        return new DeathEntry(uuid.toString(), "Alex", epoch, 3L, 500, 400,
                "minecraft:overworld", 1.0, 64.0, 2.0, category, "minecraft:" + category,
                "Alex died", "", "", "survival", 5, "", "", 0);
    }

    // --------------------------------------------------------------- S1

    @Test
    void storyMutationsPreserveWitnessesAndContext() throws IOException {
        String copy = section(source("CensusData.java"),
                "private static DeathEntry copyWithStory(", "\n    /** Snapshot of all players");
        assertTrue(copy.contains("e.witnesses()"), copy);
        assertTrue(copy.contains("e.context()"), copy);

        List<WitnessRef> witnesses = List.of(new WitnessRef("w1", "Bailey"));
        DeathContext context = new DeathContext("minecraft:sword", List.of("minecraft:helmet"),
                List.of("minecraft:speed"), "minecraft:plains", "clear");
        DeathEntry original = new DeathEntry(UUID_A.toString(), "Alex", 1000L, 3L, 500, 400,
                "minecraft:overworld", 1.0, 64.0, 2.0, "fall", "minecraft:fall", "fell",
                "", "", "survival", 5, "", "", 0, witnesses, context);
        CensusData data = new CensusData();
        data.recordDeath(original, 10);
        assertTrue(data.setExcuse(UUID_A, 1000L, "oops"));
        assertTrue(data.addRespect(UUID_A, 1000L));
        DeathEntry updated = data.playerOrNull(UUID_A).deaths.get(0);
        assertEquals(witnesses, updated.witnesses(), "excuse/respect must not drop witnesses");
        assertEquals(context, updated.context(), "excuse/respect must not drop context");
        assertEquals("oops", updated.excuse());
        assertEquals(1, updated.fCount());
    }

    // --------------------------------------------------------------- S2

    @Test
    void chronicleCountersUseStoredDeathsWhileMilestonesCountVanillaBaseline() throws IOException {
        String tracker = source("DeathTracker.java");
        assertTrue(tracker.contains("int storedLifetimeDeathsBefore"), tracker);
        assertTrue(tracker.contains("new ChronicleRules.Counters(storedLifetimeDeathsBefore"), tracker);
        assertTrue(tracker.contains("Milestones.evaluate(\n                    totalDeathsBefore"), tracker);

        CensusData data = new CensusData();
        data.player(UUID_A).lastKnownName = "Alex";
        data.importVanillaBaselines(Map.of(UUID_A, new VanillaStats(100, Map.of())), false);
        for (int i = 1; i <= 99; i++) {
            data.recordDeath(death(UUID_A, i, "fall"), 1000);
        }
        assertEquals(199L, data.lifetimeDeaths(), "vanilla baseline counts for chat milestones");
        assertEquals(99L, data.storedLifetimeDeaths(), "chronicle counters exclude the baseline");
        DeathEntry next = death(UUID_A, 100, "fall");

        List<ChronicleEvent> storedRows = ChronicleRules.eventsFor(next,
                new ChronicleRules.Counters((int) data.storedLifetimeDeaths(), false, 0, 0));
        assertTrue(storedRows.stream().anyMatch(e -> e.kind() == ChronicleEvent.Kind.TOTAL_COUNT
                && e.count() == 100), "stored counter must fire the 100th chronicle row");
        List<ChronicleEvent> lifetimeRows = ChronicleRules.eventsFor(next,
                new ChronicleRules.Counters((int) data.lifetimeDeaths(), false, 0, 0));
        assertTrue(lifetimeRows.stream().anyMatch(e -> e.kind() == ChronicleEvent.Kind.TOTAL_COUNT
                && e.count() == 200), "the all-time counter would fabricate a baseline-driven row");

        List<Milestones.Milestone> chat = Milestones.evaluate(
                (int) data.lifetimeDeaths(), 99, 0, 0, next);
        assertTrue(chat.stream().anyMatch(m -> m.kind() == Milestones.Kind.TOTAL_COUNT
                && m.value() == 200), "milestone messaging keeps the all-time total");
    }

    // --------------------------------------------------------------- S3

    @Test
    void rarestAwardDetailComesFromThePeriodSnapshot() throws IOException {
        String commands = source("CensusCommands.java");
        assertTrue(commands.contains("static List<AwardResult> computeAwardsFrom("), commands);
        assertTrue(commands.contains("CensusStats.rarestCause(\n                fullPeriodSnapshot"), commands);
        assertTrue(commands.contains("computeAwards(server, publicPlayers, inPeriod, false)"), commands);
        assertTrue(commands.contains("computeAwards(server, publicInRange, inRange, true)"), commands);
        assertTrue(source("CensusExport.java").contains(
                "computeAwards(server, players, allPlayers, false)"));
        assertTrue(source("DiscordNotifier.java").contains(
                "computeAwards(server, publicRecent, fullRecent, true)"));

        List<DeathEntry> alexPeriod = List.of(
                death(UUID_A, 1000L, "fall"), death(UUID_A, 2000L, "fall"));
        UUID uuidB = UUID.fromString("00000000-0000-0000-0000-000000000002");
        List<DeathEntry> boPeriod = List.of(death(uuidB, 3000L, "lightning"));
        List<DeathEntry> boAllTime = List.of(
                death(uuidB, 1000L, "lightning"), death(uuidB, 2000L, "lightning"),
                death(uuidB, 3000L, "lightning"), death(uuidB, 4000L, "lightning"),
                death(uuidB, 5000L, "lightning"));
        List<PlayerDeaths> period = List.of(
                new PlayerDeaths(UUID_A.toString(), "Alex", false, false, false, false, alexPeriod),
                new PlayerDeaths(uuidB.toString(), "Bo", false, false, false, false, boPeriod));
        List<PlayerDeaths> allTime = List.of(
                new PlayerDeaths(UUID_A.toString(), "Alex", false, false, false, false, alexPeriod),
                new PlayerDeaths(uuidB.toString(), "Bo", false, false, false, false, boAllTime));
        assertEquals("lightning", CensusStats.rarestCause(period, true).orElse(""));
        assertEquals("fall", CensusStats.rarestCause(allTime, true).orElse(""));

        List<AwardResult> periodResults = CensusCommands.computeAwardsFrom(
                period, period, Map.of(), 1);
        List<AwardResult> allTimeResults = CensusCommands.computeAwardsFrom(
                period, allTime, Map.of(), 1);
        AwardResult periodRarest = periodResults.stream()
                .filter(r -> r.rule() == Awards.Rule.RAREST_CAUSE)
                .findFirst().orElseThrow();
        AwardResult allTimeRarest = allTimeResults.stream()
                .filter(r -> r.rule() == Awards.Rule.RAREST_CAUSE)
                .findFirst().orElseThrow();
        assertEquals("lightning", periodRarest.detail(), "period rarity, not all-time rarity");
        assertEquals("fall", allTimeRarest.detail());
        assertFalse(periodRarest.detail().equals(allTimeRarest.detail()));
    }

    // --------------------------------------------------------------- S4 / S5

    @Test
    void ownStatsTreatsPlayerExistenceAsTheGate() throws IOException {
        String commands = source("CensusCommands.java");
        String own = section(commands, "private static int ownStats(", "private static int serverStats(");
        assertTrue(own.contains("if (pc == null) {"), own);
        assertFalse(own.contains("pc.deaths.isEmpty()"), own);
        String ui = source("ui/DialogUiContext.java");
        assertTrue(ui.contains("public int awardsMinCount()"), ui);
        assertTrue(ui.contains("return Config.awardsMinCount();"), ui);
    }

    // --------------------------------------------------------------- S6

    @Test
    void dimensionArgumentAcceptsNamespacedIdAtExecution() throws Exception {
        assertTrue(sourceHasDimensionArgument(), "hotspots must use the colon-accepting argument");
        String[] captured = new String[1];
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.<Object>literal("hotspots")
                .then(RequiredArgumentBuilder.<Object, String>argument("dimension",
                        CensusCommands.DimensionArgumentType.dimension())
                        .executes(ctx -> {
                            captured[0] = ctx.getArgument("dimension", String.class);
                            return 1;
                        })));
        assertEquals(1, dispatcher.execute("hotspots minecraft:the_nether", new Object()));
        assertEquals("minecraft:the_nether", captured[0]);
        assertEquals(1, dispatcher.execute("hotspots minecraft:the_end", new Object()));
        assertEquals("minecraft:the_end", captured[0]);
    }

    private static boolean sourceHasDimensionArgument() {
        try {
            return source("CensusCommands.java").contains(
                    "argument(\"dimension\", DimensionArgumentType.dimension())");
        } catch (IOException e) {
            return false;
        }
    }

    // --------------------------------------------------------------- M1 / M2

    @Test
    void reconstructionHonorsConfiguredCapAndZeroCounter() {
        assertFalse(new CensusData().lifetimeDeathsPresent());
        CensusData withDeath = new CensusData();
        withDeath.recordDeath(death(UUID_A, 1000L, "fall"), 10);
        assertTrue(withDeath.lifetimeDeathsPresent());

        DynamicOps<Tag> ops = NbtOps.INSTANCE;
        CompoundTag root = (CompoundTag) CensusData.CODEC.encodeStart(ops, withDeath).getOrThrow();
        root.putLong("lifetimeDeaths", 0L);
        CensusData zero = CensusData.CODEC.parse(ops, root).getOrThrow();
        assertEquals(1, zero.chronicleEvents().size(), "zero counter is not a stored lifetime");
        assertEquals(ChronicleEvent.Kind.SERVER_FIRST, zero.chronicleEvents().get(0).kind());

        root.putLong("lifetimeDeaths", Integer.MAX_VALUE);
        CensusData capped = CensusData.CODEC.parse(ops, root).getOrThrow();
        assertFalse(capped.chronicleEvents().isEmpty());
        assertTrue(capped.chronicleEvents().size() <= Config.chronicleMaxEvents(),
                "reconstruction must honor the configured retention cap");
        assertTrue(capped.chronicleEvents().stream().anyMatch(ChronicleRules::isUndated));
    }

    // --------------------------------------------------------------- M3 / M13

    @Test
    void backupRestoreSkipsInvalidEpochsAndClampsWitnesses() {
        StringBuilder witnesses = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            if (i > 0) {
                witnesses.append(',');
            }
            witnesses.append("{\"uuid\":\"w").append(i).append("\",\"nameAtDeath\":\"W").append(i)
                    .append("\"}");
        }
        String json = "{\"players\":[{\"uuid\":\"" + UUID_A + "\",\"name\":\"Alex\",\"deaths\":["
                + "{\"epochMillis\":1000,\"causeCategory\":\"fall\",\"witnesses\":[" + witnesses + "]},"
                + "{\"epochMillis\":0,\"causeCategory\":\"fall\"},"
                + "{\"epochMillis\":-5,\"causeCategory\":\"fall\"},"
                + "{\"causeCategory\":\"fall\"}]}],"
                + "\"chronicleEvents\":[{\"epochMillis\":0,\"count\":100},"
                + "{\"epochMillis\":2000,\"count\":1}]}";
        List<ChronicleEvent> chronicle = new ArrayList<>();
        List<PlayerDeaths> parsed = CensusExport.parseBackupJson(json, chronicle);
        assertNotNull(parsed);
        assertEquals(1, parsed.size());
        assertEquals(1, parsed.get(0).deaths().size(), "only the positive-epoch record survives");
        assertEquals(1000L, parsed.get(0).deaths().get(0).epochMillis());
        assertEquals(DeathEntry.MAX_WITNESSES, parsed.get(0).deaths().get(0).witnesses().size());
        assertEquals(1, chronicle.size(), "epochless chronicle rows are skipped");
        assertEquals(2000L, chronicle.get(0).epochMillis());
    }

    @Test
    void codecSkipsInvalidEpochsAndClampsWitnesses() throws IOException {
        DynamicOps<Tag> ops = NbtOps.INSTANCE;
        CensusData data = new CensusData();
        data.recordDeath(death(UUID_A, 1000L, "fall"), 10);
        CompoundTag root = (CompoundTag) CensusData.CODEC.encodeStart(ops, data).getOrThrow();
        CompoundTag player = root.getCompoundOrEmpty("players").getCompoundOrEmpty(UUID_A.toString());
        ListTag deaths = player.getListOrEmpty("deaths");
        CompoundTag valid = deaths.getCompoundOrEmpty(0);
        CompoundTag zero = (CompoundTag) valid.copy();
        zero.putLong("epochMillis", 0L);
        deaths.add(zero);
        CompoundTag negative = (CompoundTag) valid.copy();
        negative.putLong("epochMillis", -5L);
        deaths.add(negative);

        // 20 NBT witnesses must be truncated to the canonical bound.
        ListTag witnessTags = new ListTag();
        for (int i = 0; i < 20; i++) {
            CompoundTag w = new CompoundTag();
            w.putString("uuid", "w" + i);
            w.putString("nameAtDeath", "W" + i);
            witnessTags.add(w);
        }
        valid.put("witnesses", witnessTags);

        CensusData loaded = CensusData.CODEC.parse(ops, root).getOrThrow();
        List<DeathEntry> restored = loaded.playerOrNull(UUID_A).deaths;
        assertEquals(1, restored.size(), "epochless death records must be skipped");
        assertEquals(1000L, restored.get(0).epochMillis());
        assertEquals(DeathEntry.MAX_WITNESSES, restored.get(0).witnesses().size());
        assertTrue(source("CensusData.java").contains("ChronicleRules.isValidEpoch(epochMillis)"));
        assertTrue(source("CensusData.java").contains("WitnessRef.clamp(decoded, DeathEntry.MAX_WITNESSES)"));
        assertTrue(source("CensusExport.java").contains(
                "WitnessRef.clamp(out, DeathEntry.MAX_WITNESSES)"));
    }

    // --------------------------------------------------------------- M6 / M14

    @Test
    void cancelledTotemIsIgnoredAndHiddenDictionaryIsGated() throws IOException {
        String totem = section(source("DeathTracker.java"),
                "public static void onTotemUsed(", "\n    /** Config-gated, bounded");
        assertTrue(totem.contains("if (event.isCanceled())"), totem);
        String tracker = source("DeathTracker.java");
        assertTrue(tracker.contains("Config.discordNotifyDeaths()"), tracker);
        assertTrue(tracker.contains("DiscordNotifier.realtimeWebhookConfigured()"), tracker);
        assertFalse(DiscordNotifier.realtimeWebhookConfigured("", ""));
        assertTrue(DiscordNotifier.realtimeWebhookConfigured(WEBHOOK, ""));
    }

    // --------------------------------------------------------------- M9 / M10

    @Test
    void loopMinimumIsRangeTwoAndCompareRowsArePaired() throws IOException {
        assertTrue(source("Config.java").contains(
                "defineInRange(\"deathLoopMinDeaths\", 3, 2, 100)"));
        List<String> pairs = DialogUi.compareRowPairs(new CompareView("all", "Alex", "Bo",
                List.of("Deaths: 3", "Top cause: fall"), List.of("Deaths: 7"), "", "token"));
        assertEquals(List.of("Deaths: 3 | Deaths: 7", "Top cause: fall | "), pairs);
        assertEquals(List.of(), DialogUi.compareRowPairs(null));
    }
}
