package com.pengin0503.deathcensus;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runtime regressions for the 1.21.1 round of review fixes. */
class ReviewFixesTest {
    private static final String UUID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String UUID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    private static String source(String file) throws IOException {
        String dir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(dir, "deathcensus.mainSourceDir system property is not set");
        return Files.readString(Path.of(dir, "com", "pengin0503", "deathcensus", file));
    }

    private static DeathEntry death(String uuid, String name, long epoch, String cause) {
        return new DeathEntry(uuid, name, epoch, 1L, 100, 90, "minecraft:overworld",
                1.0, 64.0, 2.0, cause, "minecraft:" + cause, name + " died",
                "", "", "survival", 0, "", "", 0);
    }

    private static String rarestDetail(List<AwardResult> rows) {
        for (AwardResult row : rows) {
            if (row.rule() == Awards.Rule.RAREST_CAUSE) {
                return row.detail();
            }
        }
        return "";
    }

    private static boolean hasTotalCountRow(List<ChronicleEvent> rows) {
        return rows.stream().anyMatch(e -> e.kind() == ChronicleEvent.Kind.TOTAL_COUNT);
    }

    @Test
    void vanillaBaselineNeverInflatesChronicleTotals() {
        UUID uuid = UUID.fromString(UUID_A);
        CensusData data = new CensusData();
        for (int i = 0; i < 98; i++) {
            data.recordDeath(death(uuid.toString(), "A", 1000L + i, "fall"), 10_000);
        }
        data.player(uuid).vanillaDeathsBaseline = 1;
        assertEquals(99L, data.lifetimeDeaths(), "milestone total stays baseline-inflated");
        assertEquals(98L, data.storedLifetimeDeaths());
        int storedBefore = DeathTracker.chronicleTotalBefore(data);
        assertEquals(98, storedBefore);

        DeathEntry next = death(uuid.toString(), "A", 2000L, "fall");
        List<ChronicleEvent> storedRows = ChronicleRules.eventsFor(next,
                new ChronicleRules.Counters(storedBefore, false, 1, 1));
        assertFalse(hasTotalCountRow(storedRows), "no 100th-death row from a baseline-only milestone");
        List<ChronicleEvent> inflatedRows = ChronicleRules.eventsFor(next,
                new ChronicleRules.Counters((int) data.lifetimeDeaths(), false, 1, 1));
        assertTrue(hasTotalCountRow(inflatedRows));

        CensusData later = new CensusData();
        for (int i = 0; i < 199; i++) {
            later.recordDeath(death(uuid.toString(), "A", 3000L + i, "fall"), 10_000);
        }
        later.player(uuid).vanillaDeathsBaseline = 1;
        assertEquals(200L, later.lifetimeDeaths());
        int laterStored = DeathTracker.chronicleTotalBefore(later);
        List<ChronicleEvent> rows = ChronicleRules.eventsFor(next,
                new ChronicleRules.Counters(laterStored, false, 1, 1));
        ChronicleEvent total = rows.stream()
                .filter(e -> e.kind() == ChronicleEvent.Kind.TOTAL_COUNT)
                .findFirst().orElseThrow();
        assertEquals(later.storedLifetimeDeaths() + 1L, (long) total.count(),
                "chronicle count must follow the stored count");
        assertNotEquals(later.lifetimeDeaths() + 1L, (long) total.count(),
                "baseline-inflated totals would announce the 201st death");
        List<ChronicleEvent> inflatedLateRows = ChronicleRules.eventsFor(next,
                new ChronicleRules.Counters((int) later.lifetimeDeaths(), false, 1, 1));
        assertFalse(hasTotalCountRow(inflatedLateRows));
    }

    @Test
    void rarestCauseAwardUsesThePeriodSnapshotNotAllTime() {
        List<DeathEntry> own = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            own.add(death(UUID_A, "A", 1000L + i, "fall"));
        }
        for (int i = 0; i < 2; i++) {
            own.add(death(UUID_A, "A", 1100L + i, "lava"));
        }
        own.add(death(UUID_A, "A", 1200L, "lightning"));
        own.add(death(UUID_A, "A", 1300L, "fire"));
        List<PlayerDeaths> publicPeriod = List.of(new PlayerDeaths(UUID_A, "A", false, false, false, false, own));

        List<DeathEntry> periodOptOut = new ArrayList<>();
        periodOptOut.add(death(UUID_B, "B", 1400L, "fire"));
        periodOptOut.add(death(UUID_B, "B", 1401L, "fire"));
        List<PlayerDeaths> fullPeriod = new ArrayList<>(publicPeriod);
        fullPeriod.add(new PlayerDeaths(UUID_B, "B", true, false, false, false, periodOptOut));

        List<DeathEntry> older = new ArrayList<>();
        older.add(death(UUID_B, "B", 500L, "lightning"));
        older.add(death(UUID_B, "B", 501L, "lightning"));
        older.add(death(UUID_B, "B", 502L, "lightning"));
        List<PlayerDeaths> allTime = new ArrayList<>(fullPeriod);
        allTime.add(new PlayerDeaths(UUID_B, "B", true, false, false, false, older));

        assertEquals("lightning", CensusStats.rarestCause(fullPeriod, true).orElseThrow());
        assertEquals("lava", CensusStats.rarestCause(allTime, true).orElseThrow());

        List<AwardResult> periodAwards = CensusCommands.computeAwardsFrom(
                publicPeriod, fullPeriod, Map.of(), 1);
        List<AwardResult> allTimeAwards = CensusCommands.computeAwardsFrom(
                publicPeriod, allTime, Map.of(), 1);
        String periodDetail = rarestDetail(periodAwards);
        String allTimeDetail = rarestDetail(allTimeAwards);
        assertEquals("lightning", periodDetail);
        assertEquals("lava", allTimeDetail);
        assertNotEquals(allTimeDetail, periodDetail);
    }

    @Test
    void ownStatsNoDeathBranchIncludesKnownPlayersWithoutDeaths() {
        CensusData.PlayerCensus pc = new CensusData.PlayerCensus();
        pc.nearDeaths.add(new NearDeathEvent(UUID_A, "A", 1000L, "minecraft:overworld",
                1.0, 64.0, 2.0, "fall", "minecraft:fall", NearDeathEvent.Kind.TOTEM));
        assertTrue(CensusCommands.ownStatsHasNoDeaths(null));
        assertTrue(CensusCommands.ownStatsHasNoDeaths(pc),
                "a near-death save alone must not send /dc stats own into the summary path");
        pc.deaths.add(death(UUID_A, "A", 1001L, "fall"));
        assertFalse(CensusCommands.ownStatsHasNoDeaths(pc));
    }

    @SuppressWarnings("unchecked")
    @Test
    void dimensionArgumentParsesAndExecutesNamespacedIds() throws Exception {
        ArgumentType<String> argument = CensusCommands.dimensionArgument();
        StringReader reader = new StringReader("minecraft:the_nether");
        assertEquals("minecraft:the_nether", argument.parse(reader));
        assertFalse(reader.canRead());

        Method root = CensusCommands.class.getDeclaredMethod("root");
        root.setAccessible(true);
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register((LiteralArgumentBuilder<CommandSourceStack>) root.invoke(null));
        String command = "deathcensus hotspots minecraft:the_nether";
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(command, null);
        assertTrue(parsed.getExceptions().isEmpty(), parsed.getExceptions().toString());
        assertFalse(parsed.getReader().canRead());

        // Actually execute the command: a null server makes the handler return 0,
        // but Brigadier still has to parse and dispatch the colon-bearing token.
        CommandSourceStack source = new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO,
                null, 2, "test", Component.literal("test"), null, null);
        assertEquals(0, dispatcher.execute(command, source));
    }

    @Test
    void cancelledTotemEventsAreIgnoredBeforeAnyNearDeathCounting() throws IOException {
        String tracker = source("DeathTracker.java");
        int start = tracker.indexOf("public static void onTotemUsed(");
        assertTrue(start > 0);
        int end = tracker.indexOf("recordNearDeath(player, event.getSource(), NearDeathEvent.Kind.TOTEM)", start);
        assertTrue(end > start);
        String body = tracker.substring(start, end);
        int cancel = body.indexOf("if (event.isCanceled())");
        assertTrue(cancel >= 0, body);
        assertTrue(cancel < body.indexOf("instanceof ServerPlayer"), body);
    }

    @Test
    void hiddenIdentityDictionaryIsBuiltOnlyForAConfiguredRealtimePost() throws IOException {
        assertTrue(Config.discordNotifyDeaths());
        assertFalse(DiscordNotifier.realtimeWebhookConfigured("", ""));
        assertTrue(DiscordNotifier.realtimeWebhookConfigured(
                "https://discord.com/api/webhooks/123/abc", ""));

        String tracker = source("DeathTracker.java");
        int start = tracker.indexOf("if (!pc.optOutPublic && !pc.discordOptOut");
        assertTrue(start > 0);
        int end = tracker.indexOf("HiddenIdentities.of(data.snapshot())", start);
        assertTrue(end > start);
        String gate = tracker.substring(start, end);
        assertTrue(gate.contains("Config.discordNotifyDeaths()"), gate);
        assertTrue(gate.contains("DiscordNotifier.realtimeWebhookConfigured()"), gate);
    }

    @Test
    void backupImportSkipsNonPositiveDeathEpochs() {
        UUID uuid = UUID.fromString(UUID_A);
        String json = "{\"players\":[{\"uuid\":\"" + uuid + "\",\"name\":\"A\",\"deaths\":["
                + "{\"epochMillis\":0,\"causeCategory\":\"fall\"},"
                + "{\"epochMillis\":-9,\"causeCategory\":\"fall\"},"
                + "{\"epochMillis\":123,\"causeCategory\":\"fall\"}]}]}";
        List<PlayerDeaths> parsed = CensusExport.parseBackupJson(json);
        assertNotNull(parsed);
        assertEquals(1, parsed.size());
        assertEquals(1, parsed.get(0).deaths().size());
        assertEquals(123L, parsed.get(0).deaths().get(0).epochMillis());
    }

    @Test
    void backupImportClampsWitnessLists() {
        UUID uuid = UUID.fromString(UUID_A);
        StringBuilder json = new StringBuilder("{\"players\":[{\"uuid\":\"" + uuid
                + "\",\"name\":\"A\",\"deaths\":[{\"epochMillis\":123,\"witnesses\":[");
        for (int i = 0; i < DeathEntry.MAX_WITNESSES + 4; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"uuid\":\"").append(UUID.randomUUID())
                    .append("\",\"nameAtDeath\":\"W").append(i).append("\"}");
        }
        json.append("]}]}]}");
        List<PlayerDeaths> parsed = CensusExport.parseBackupJson(json.toString());
        assertNotNull(parsed);
        assertEquals(DeathEntry.MAX_WITNESSES, parsed.get(0).deaths().get(0).witnesses().size());
    }
}
