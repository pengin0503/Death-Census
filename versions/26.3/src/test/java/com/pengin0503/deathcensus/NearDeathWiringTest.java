package com.pengin0503.deathcensus;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-tree wiring check for the 1.4.0 PR-11 near-death events (§10). */
class NearDeathWiringTest {
    private static String source(String file) throws IOException {
        String dir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(dir, "deathcensus.mainSourceDir system property is not set");
        return Files.readString(Path.of(dir, "com", "pengin0503", "deathcensus", file));
    }

    private static JsonObject loadLang(String lang) throws IOException {
        String path = "/assets/deathcensus/lang/" + lang + ".json";
        try (InputStream in = NearDeathWiringTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing lang resource " + path);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    private static NearDeathEvent event(UUID uuid, long epoch, NearDeathEvent.Kind kind) {
        return new NearDeathEvent(uuid.toString(), "Steve", epoch, "minecraft:overworld",
                10.0, 64.0, -20.0, "fall", "minecraft:fall", kind);
    }

    @Test
    void configDefaultsAndDeclarationsMatchSectionTen() throws IOException {
        assertTrue(Config.nearDeathCaptureEnabled());
        assertEquals(2000L, Config.nearDeathDedupWindowMillis());
        assertEquals(50, Config.nearDeathMaxPerPlayer());
        assertFalse(Config.nearDeathInDiscord());
        assertEquals(50, NearDeathAnalysis.DEFAULT_MAX_PER_PLAYER);
        assertEquals(500, NearDeathAnalysis.SERVER_CAP);
        assertEquals(2000L, NearDeathAnalysis.DEFAULT_DEDUP_WINDOW_MILLIS);

        String config = source("Config.java");
        assertTrue(config.contains("define(\"nearDeathCaptureEnabled\", true)"), config);
        assertTrue(config.contains("defineInRange(\"nearDeathDedupWindowMillis\", 2000, 500, 10000)"), config);
        assertTrue(config.contains("defineInRange(\"nearDeathMaxPerPlayer\", 50, 1, 500)"), config);
        assertTrue(config.contains("define(\"nearDeathInDiscord\", false)"), config);
        assertTrue(config.contains("never contribute to death totals"), config);
        assertTrue(config.contains("Earlier saves cannot be backfilled"), config);
    }

    @Test
    void bothObservableHooksAreInstalledWithoutTouchingDeaths() throws IOException {
        String tracker = source("DeathTracker.java");
        // Cancelled deaths arrive at a non-LOWEST listener that accepts canceled events;
        // the unchanged LOWEST recording listener therefore creates no DeathEntry.
        assertTrue(tracker.contains("@SubscribeEvent(priority = EventPriority.LOWEST)"), tracker);
        assertTrue(tracker.contains("@SubscribeEvent(receiveCanceled = true, priority = EventPriority.LOW)"), tracker);
        assertTrue(tracker.contains("onLivingDeathCancelled(LivingDeathEvent event)"), tracker);
        assertTrue(tracker.contains("if (!event.isCanceled())"), tracker);
        assertTrue(tracker.contains("NearDeathEvent.Kind.CANCELLED"), tracker);
        assertTrue(tracker.contains("onTotemUsed(net.neoforged.neoforge.event.entity.living.LivingUseTotemEvent event)"), tracker);
        assertTrue(tracker.contains("minecraft:totem_of_undying"), tracker);
        assertTrue(tracker.contains("NearDeathEvent.Kind.TOTEM"), tracker);

        int start = tracker.indexOf("// ------------------------------------------------------- near death");
        assertTrue(start > 0, "near-death section missing");
        String hooks = tracker.substring(start);
        assertFalse(hooks.contains("new DeathEntry"), hooks);
        assertFalse(hooks.contains("recordDeath("), hooks);
        assertFalse(hooks.contains("lifetimeDeaths"), hooks);
        assertFalse(hooks.contains("broadcastSystemMessage"), hooks);
    }

    @Test
    void captureIsGatedDedupedBoundedAndServerThreaded() throws IOException {
        String tracker = source("DeathTracker.java");
        assertTrue(tracker.contains("if (!Config.nearDeathCaptureEnabled() || player instanceof FakePlayer)"), tracker);
        assertTrue(tracker.contains("NearDeathAnalysis.dedupeKey("), tracker);
        assertTrue(tracker.contains("NEAR_DEATH_SEEN.size() >= MAX_NEAR_DEATH_KEYS"), tracker);
        assertTrue(tracker.contains("NEAR_DEATH_SEEN.add(key)"), tracker);
        assertTrue(tracker.contains("server.execute(() -> CensusData.get(server).addNearDeathEvent("), tracker);
        assertTrue(tracker.contains("Config.nearDeathMaxPerPlayer(), Config.nearDeathDedupWindowMillis()"), tracker);
        int reset = tracker.indexOf("void resetTransientState()");
        assertTrue(reset > 0, "resetTransientState missing");
        String resetBody = tracker.substring(reset, tracker.indexOf("\n    }\n", reset));
        assertTrue(resetBody.contains("NEAR_DEATH_SEEN.clear()"), resetBody);
    }

    @Test
    void storageRoundTripAndCapsAreWired() throws IOException {
        String data = source("CensusData.java");
        assertTrue(data.contains("public final List<NearDeathEvent> nearDeaths"), data);
        assertTrue(data.contains("addNearDeathEvent(NearDeathEvent event, int maxPerPlayer, long dedupWindowMillis)"), data);
        assertTrue(data.contains("NearDeathAnalysis.appendBounded("), data);
        assertTrue(data.contains("trimNearDeathsToServerCap()"), data);
        assertTrue(data.contains("NearDeathAnalysis.SERVER_CAP"), data);
        assertTrue(data.contains("nearDeaths()"), data);
        assertTrue(data.contains("nearDeathToTag") || data.contains("NEAR_DEATH_CODEC"), data);
        assertTrue(data.contains("nearDeathFromTag") || data.contains("decodeList(ops, map, \"nearDeaths\""), data);
    }

    @Test
    void perPlayerCapDedupeAndDeathTotalsStaySeparate() {
        UUID uuid = new UUID(0L, 11L);
        CensusData data = new CensusData();
        long base = 1_700_000_000_000L;
        for (int i = 0; i < 60; i++) {
            assertTrue(data.addNearDeathEvent(event(uuid, base + i * 10_000L,
                    NearDeathEvent.Kind.TOTEM), 50, 2000L));
        }
        assertEquals(50, data.player(uuid).nearDeaths.size());
        assertEquals(base + 10 * 10_000L, data.player(uuid).nearDeaths.get(0).epochMillis());

        NearDeathEvent first = event(uuid, base + 1_000_000L, NearDeathEvent.Kind.TOTEM);
        assertTrue(data.addNearDeathEvent(first, 50, 2000L));
        NearDeathEvent twin = new NearDeathEvent(first.uuid(), first.name(), first.epochMillis() + 500L,
                first.dimension(), first.x(), first.y(), first.z(), first.causeCategory(),
                first.causeId(), NearDeathEvent.Kind.CANCELLED);
        assertFalse(data.addNearDeathEvent(twin, 50, 2000L));
        assertEquals(50, data.player(uuid).nearDeaths.size());
        assertTrue(data.player(uuid).nearDeaths.contains(first));
        assertEquals(0, data.lifetimeDeaths());
        assertEquals(0, data.player(uuid).deaths.size());
    }

    @Test
    void serverWideCapDropsOldestAcrossPlayersOnly() {
        CensusData data = new CensusData();
        long base = 1_700_000_000_000L;
        for (int p = 0; p < 11; p++) {
            UUID uuid = new UUID(0L, p + 100L);
            for (int i = 0; i < 50; i++) {
                assertTrue(data.addNearDeathEvent(event(uuid, base + p * 1_000_000L + i * 10_000L,
                        NearDeathEvent.Kind.CANCELLED), 50, 2000L));
            }
        }
        int total = 0;
        for (CensusData.PlayerCensus pc : data.allPlayers().values()) {
            total += pc.nearDeaths.size();
        }
        assertEquals(500, total);
        assertEquals(0, data.lifetimeDeaths());
    }

    @Test
    void publicViewerGetsRedactedNearDeathEvents() {
        NearDeathEvent event = event(new UUID(0L, 22L), 1_700_000_000_000L, NearDeathEvent.Kind.TOTEM);
        PlayerPrivacy privacy = new PlayerPrivacy(false, true, false, false);
        NearDeathEvent view = DeathRedactor.apply(event, privacy, false, HiddenIdentities.empty());

        assertTrue(DeathRedactor.isNearDeathCoordinateHidden(view));
        assertEquals("~, ~, ~", NearDeathAnalysis.positionLabel(view));
        assertEquals(10.0, event.x());
        assertSame(event, DeathRedactor.apply(event, privacy, true, HiddenIdentities.empty()));
    }

    @Test
    void commandStatsRankingAndExportsAreWired() throws IOException {
        String commands = source("CensusCommands.java");
        assertTrue(commands.contains("literal(\"neardeath\")"), commands);
        assertTrue(commands.contains("command.deathcensus.neardeath.stats"), commands);
        assertTrue(commands.contains("DeathRedactor.apply(raw, privacy, false, hidden)"), commands);
        assertTrue(commands.contains("if (server == null || !Config.nearDeathCaptureEnabled())"), commands);

        Path dir = Path.of(System.getProperty("deathcensus.mainSourceDir"), "com", "pengin0503", "deathcensus", "ui");
        String ui = Files.exists(dir.resolve("ChatUi.java"))
                ? source("ui/ChatUi.java") : source("ui/DialogUi.java");
        String ctx = Files.exists(dir.resolve("ChatUiContext.java"))
                ? source("ui/ChatUiContext.java") : source("ui/DialogUiContext.java");
        assertTrue(ui.contains("command.deathcensus.neardeath.stats"), ui);
        assertTrue(ui.contains("command.deathcensus.ui.ranking.neardeath"), ui);
        assertTrue(ctx.contains("nearDeathEnabled()"), ctx);

        String export = source("CensusExport.java");
        assertTrue(export.contains("ExportRenderers.toJsonWithChronicle("), export);
        assertTrue(export.contains("ExportRenderers.toCsvWithChronicle("), export);
        assertTrue(export.contains("ExportRenderers.toHtmlWithChronicle("), export);
        assertTrue(export.contains("nearDeaths(p.get(\"nearDeaths\"))"), export);
        assertTrue(source("DiscordNotifier.java").indexOf("nearDeath") < 0,
                "near deaths must not change the realtime Discord death line");
    }

    @Test
    void allNearDeathKeysExistInBothBundles() throws IOException {
        JsonObject en = loadLang("en_us");
        JsonObject ja = loadLang("ja_jp");
        List<String> keys = List.of(
                "command.deathcensus.neardeath.help",
                "command.deathcensus.neardeath.header",
                "command.deathcensus.neardeath.kind.totem",
                "command.deathcensus.neardeath.kind.cancelled",
                "command.deathcensus.neardeath.row",
                "command.deathcensus.neardeath.empty",
                "command.deathcensus.neardeath.stats",
                "command.deathcensus.ui.ranking.neardeath");
        for (String key : keys) {
            assertTrue(en.has(key), "missing en_us entry for " + key);
            assertTrue(ja.has(key), "missing ja_jp entry for " + key);
            assertFalse(en.get(key).getAsString().isBlank(), "blank en_us entry for " + key);
            assertFalse(ja.get(key).getAsString().isBlank(), "blank ja_jp entry for " + key);
        }
        assertEquals(295, en.size());
        assertEquals(295, ja.size());
        assertEquals(en.keySet(), ja.keySet());
    }
}
