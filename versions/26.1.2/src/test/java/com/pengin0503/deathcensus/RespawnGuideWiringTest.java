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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-tree wiring check for the 1.4.0 PR-10 respawn return guide (§9). */
class RespawnGuideWiringTest {
    private static String source(String file) throws IOException {
        String dir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(dir, "deathcensus.mainSourceDir system property is not set");
        return Files.readString(Path.of(dir, "com", "pengin0503", "deathcensus", file));
    }

    private static JsonObject loadLang(String lang) throws IOException {
        String path = "/assets/deathcensus/lang/" + lang + ".json";
        try (InputStream in = RespawnGuideWiringTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing lang resource " + path);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    @Test
    void configDefaultsMatchSectionFifteen() {
        assertTrue(Config.enabled());
        assertTrue(Config.respawnGuideEnabled());
        assertEquals(6000, Config.respawnGuideMaxTicks());
        assertEquals(16, Config.respawnGuideArrivalBlocks());
        assertEquals(20, Config.respawnGuideRefreshTicks());
        assertEquals(6000, RespawnGuideText.DEFAULT_MAX_TICKS);
        assertEquals(16, RespawnGuideText.DEFAULT_ARRIVAL_BLOCKS);
        assertEquals(20, RespawnGuideText.DEFAULT_REFRESH_TICKS);
    }

    @Test
    void configDeclaresAllFourKeys() throws IOException {
        String config = source("Config.java");
        assertTrue(config.contains("define(\"respawnGuideEnabled\", true)"), config);
        assertTrue(config.contains("defineInRange(\"respawnGuideMaxTicks\", 6000, 0, 12000)"), config);
        assertTrue(config.contains("defineInRange(\"respawnGuideArrivalBlocks\", 16, 0, 256)"), config);
        assertTrue(config.contains("defineInRange(\"respawnGuideRefreshTicks\", 20, 1, 200)"), config);
    }

    @Test
    void deathHookWritesTransientSelfOnlyStateAndLeavesLoopRingIntact() throws IOException {
        String tracker = source("DeathTracker.java");
        assertTrue(tracker.contains("private static final RespawnGuideTracker GUIDE_TRACKER"), tracker);
        assertTrue(tracker.contains("GUIDE_TRACKER.begin(player.getUUID(), entry.dimension(), player.getX(), player.getY(),"), tracker);
        assertTrue(tracker.contains("player.getZ(), deathEpochMillis, guideTick(player), Config.respawnGuideMaxTicks());"), tracker);
        assertTrue(tracker.contains("if (Config.enabled() && Config.respawnGuideEnabled())"), tracker);
        // §9 privacy: the guide is written for every victim, unlike the public-location loop ring.
        assertTrue(tracker.contains("LOOP_TRACKER.observe(mark,"), tracker);
        assertTrue(tracker.contains("Config.deathLoopEnabled() && !pc.optOutPublic && !pc.hideCoordinates"), tracker);
        assertFalse(tracker.contains("GUIDE_TRACKER.begin(player.getUUID(), entry.dimension(), player.getX(), player.getY(),\n                    witnesses"), tracker);
    }

    @Test
    void respawnLogoutAndServerStopClearTheState() throws IOException {
        String tracker = source("DeathTracker.java");
        assertTrue(tracker.contains("onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event)"), tracker);
        assertTrue(tracker.contains("onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event)"), tracker);
        assertTrue(tracker.contains("GUIDE_TRACKER.remove(player.getUUID())"), tracker);
        assertTrue(tracker.contains("GUIDE_TRACKER.pruneExpired(tick)"), tracker);
        assertTrue(tracker.contains("state.expired(tick)"), tracker);
        assertTrue(tracker.contains("GUIDE_TRACKER.clear()"), tracker);
        assertTrue(source("DeathCensus.java").contains("DeathTracker.tickGuides(event.getServer())"));
    }

    @Test
    void guideIsVictimOnlyAndUsesOverlayForTheActionBar() throws IOException {
        String tracker = source("DeathTracker.java");
        assertTrue(tracker.contains("player.sendSystemMessage(ServerLang.tr(player, \"command.deathcensus.respawn.chat\","), tracker);
        assertTrue(tracker.contains("player.displayClientMessage(ServerLang.tr(player, \"command.deathcensus.respawn.actionbar\",")
                || tracker.contains("player.sendSystemMessage(ServerLang.tr(player, \"command.deathcensus.respawn.actionbar\","), tracker);
        assertTrue(tracker.contains("RespawnGuideText.signedHeight(height), remaining), true);"), tracker);
        assertTrue(tracker.contains("for (ServerPlayer player : server.getPlayerList().getPlayers())"), tracker);
        assertTrue(tracker.contains("RespawnGuideState state = GUIDE_TRACKER.get(uuid)"), tracker);
        assertFalse(tracker.contains("broadcastSystemMessage(ServerLang.tr(player,\n                            \"command.deathcensus.respawn"), tracker);
        assertFalse(tracker.contains("broadcastSystemMessage(ServerLang.tr(player, \"command.deathcensus.respawn"), tracker);
        String guideCode = tracker.substring(tracker.indexOf("onPlayerRespawn"),
                tracker.indexOf("private static void announceMilestone"));
        assertFalse(guideCode.contains("witnesses") || guideCode.contains("WitnessRef"), guideCode);
    }

    @Test
    void dimensionMismatchArrivalAndThrottleFollowSectionNine() throws IOException {
        String tracker = source("DeathTracker.java");
        assertTrue(tracker.contains("!state.sameDimension(dimension)"), tracker);
        assertTrue(tracker.contains("command.deathcensus.respawn.different_dimension"), tracker);
        assertTrue(tracker.contains(".dimension()."), tracker);
        assertTrue(tracker.contains(".identifier().toString()") || tracker.contains(".location().toString()"), tracker);
        assertTrue(tracker.contains("RespawnGuideText.arrived(dx, dz, Config.respawnGuideArrivalBlocks())"), tracker);
        assertTrue(tracker.contains("command.deathcensus.respawn.arrival"), tracker);
        assertTrue(tracker.contains("state.dueForRefresh(tick, Config.respawnGuideRefreshTicks())"), tracker);
        assertTrue(tracker.contains("RespawnGuideText.remainingTime(state.remainingTicks(tick))"), tracker);
    }

    @Test
    void allRespawnKeysExistInBothBundles() throws IOException {
        JsonObject en = loadLang("en_us");
        JsonObject ja = loadLang("ja_jp");
        List<String> keys = List.of(
                "command.deathcensus.respawn.chat",
                "command.deathcensus.respawn.actionbar",
                "command.deathcensus.respawn.different_dimension",
                "command.deathcensus.respawn.arrival");
        for (String key : keys) {
            assertTrue(en.has(key), "missing en_us entry for " + key);
            assertTrue(ja.has(key), "missing ja_jp entry for " + key);
            assertFalse(en.get(key).getAsString().isBlank(), "blank en_us entry for " + key);
            assertFalse(ja.get(key).getAsString().isBlank(), "blank ja_jp entry for " + key);
        }
        assertEquals(4, keys.size());
        assertEquals(315, en.size());
        assertEquals(315, ja.size());
        assertEquals(en.keySet(), ja.keySet());
    }
}
