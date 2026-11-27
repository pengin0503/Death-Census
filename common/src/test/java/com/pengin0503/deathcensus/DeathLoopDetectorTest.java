package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN 1.4.0 §8: rule edges, ring eviction, dedupe and privacy composition. */
class DeathLoopDetectorTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final long T0 = 1_700_000_000_000L;
    private static final long MINUTE = 60_000L;

    private static DeathLoopSample sample(String uuid, long epoch, double x, double z) {
        return sample(uuid, epoch, x, z, OVERWORLD, "fall");
    }

    private static DeathLoopSample sample(String uuid, long epoch, double x, double z,
                                          String dimension, String cause) {
        return new DeathLoopSample(uuid, "Player-" + uuid, epoch, x, 64.0, z, dimension, cause);
    }

    private static DeathEntry death(long epoch, double x, double z, String cause) {
        return new DeathEntry("u1", "Victim", epoch, 0L, 0, 0, OVERWORLD, x, 64.0, z,
                cause, "minecraft:" + cause, "died", "", "", "survival", 0, "", "", 0);
    }

    private static PlayerDeaths player(String uuid, boolean optOut, boolean hideCoordinates,
                                       DeathEntry... deaths) {
        return new PlayerDeaths(uuid, "Player-" + uuid, optOut, hideCoordinates,
                false, false, List.of(deaths));
    }

    @Test
    void detectsThreeConsecutiveDeathsWithinDefaultWindowAndRadius() {
        List<DeathLoop> loops = DeathLoopDetector.findLoopsFromSamples(List.of(
                sample("u", T0, 0, 0),
                sample("u", T0 + 5 * MINUTE, 10, 0),
                sample("u", T0 + 10 * MINUTE, 20, 4)));
        assertEquals(1, loops.size());
        DeathLoop loop = loops.get(0);
        assertEquals("u", loop.uuid());
        assertEquals(OVERWORLD, loop.dimension());
        assertEquals(10, loop.centerX());
        assertEquals(1, loop.centerZ());
        assertEquals(3, loop.count());
        assertEquals(T0, loop.firstEpoch());
        assertEquals(T0 + 10 * MINUTE, loop.lastEpoch());
        assertEquals("fall", loop.topCause());
    }

    @Test
    void interveningDeathOfSamePlayerElsewhereBreaksTheRun() {
        List<DeathLoop> loops = DeathLoopDetector.findLoopsFromSamples(List.of(
                sample("u", T0, 0, 0),
                sample("u", T0 + MINUTE, 1, 0),
                sample("u", T0 + 2 * MINUTE, 2, 0),
                sample("u", T0 + 3 * MINUTE, 500, 500),
                sample("u", T0 + 4 * MINUTE, 1, 1),
                sample("u", T0 + 5 * MINUTE, 2, 2)),
                DeathLoopDetector.DEFAULT_RADIUS_BLOCKS,
                DeathLoopDetector.DEFAULT_WINDOW_MILLIS, 3);
        assertEquals(1, loops.size());
        assertEquals(3, loops.get(0).count());
        assertEquals(T0, loops.get(0).firstEpoch());
    }

    @Test
    void deathsOfOtherPlayersDoNotBreakTheRun() {
        List<DeathLoop> loops = DeathLoopDetector.findLoopsFromSamples(List.of(
                sample("u", T0, 0, 0),
                sample("other", T0 + MINUTE, 500, 500),
                sample("u", T0 + 2 * MINUTE, 1, 0),
                sample("u", T0 + 3 * MINUTE, 2, 0)), 48, 10 * MINUTE, 3);
        assertEquals(1, loops.size());
        assertEquals("u", loops.get(0).uuid());
        assertEquals(3, loops.get(0).count());
    }

    @Test
    void radiusIsMeasuredFromTheFirstDeathAndEdgeIsInclusive() {
        List<DeathLoopSample> inside = List.of(
                sample("u", T0, 0, 0),
                sample("u", T0 + MINUTE, 48, 0),
                sample("u", T0 + 2 * MINUTE, 0, 48));
        assertEquals(1, DeathLoopDetector.findLoopsFromSamples(inside, 48, 10 * MINUTE, 3).size());
        List<DeathLoopSample> outside = List.of(
                sample("u", T0, 0, 0),
                sample("u", T0 + MINUTE, 48, 0),
                sample("u", T0 + 2 * MINUTE, 48.01, 0));
        List<DeathLoop> loops = DeathLoopDetector.findLoopsFromSamples(outside, 48, 10 * MINUTE, 2);
        assertEquals(1, loops.size());
        assertEquals(2, loops.get(0).count());
        assertEquals(T0 + MINUTE, loops.get(0).lastEpoch());
        assertEquals(1, DeathLoopDetector.findLoopsFromSamples(outside, 64, 10 * MINUTE, 3).size());
    }

    @Test
    void consecutiveWindowGapEdgeIsInclusiveAndOutsideBreaks() {
        List<DeathLoopSample> exact = List.of(
                sample("u", T0, 0, 0),
                sample("u", T0 + 10 * MINUTE, 1, 0),
                sample("u", T0 + 20 * MINUTE, 2, 0));
        assertEquals(1, DeathLoopDetector.findLoopsFromSamples(exact, 48, 10 * MINUTE, 3).size());
        List<DeathLoopSample> late = List.of(
                sample("u", T0, 0, 0),
                sample("u", T0 + 10 * MINUTE + 1, 1, 0),
                sample("u", T0 + 20 * MINUTE + 1, 2, 0));
        assertTrue(DeathLoopDetector.findLoopsFromSamples(late, 48, 10 * MINUTE, 3).isEmpty());
        assertEquals(1, DeathLoopDetector.findLoopsFromSamples(late, 48, 15 * MINUTE, 3).size());
    }

    @Test
    void dimensionChangeBreaksTheRun() {
        List<DeathLoop> loops = DeathLoopDetector.findLoopsFromSamples(List.of(
                sample("u", T0, 0, 0, OVERWORLD, "fall"),
                sample("u", T0 + MINUTE, 1, 0, NETHER, "lava"),
                sample("u", T0 + 2 * MINUTE, 2, 0, OVERWORLD, "fall"),
                sample("u", T0 + 3 * MINUTE, 3, 0, OVERWORLD, "fall")), 48, 10 * MINUTE, 2);
        assertEquals(1, loops.size());
        assertEquals(2, loops.get(0).count());
        assertEquals(OVERWORLD, loops.get(0).dimension());
    }

    @Test
    void minimumThresholdAndLongestFirstTieBreak() {
        List<DeathLoop> loops = DeathLoopDetector.findLoopsFromSamples(List.of(
                sample("short", T0 + 5 * MINUTE, 0, 0),
                sample("short", T0 + 6 * MINUTE, 1, 0),
                sample("short", T0 + 7 * MINUTE, 2, 0),
                sample("long", T0, 100, 100),
                sample("long", T0 + MINUTE, 101, 100),
                sample("long", T0 + 2 * MINUTE, 102, 100),
                sample("long", T0 + 3 * MINUTE, 103, 100)), 48, 10 * MINUTE, 3);
        assertEquals(2, loops.size());
        assertEquals(4, loops.get(0).count());
        assertEquals("long", loops.get(0).uuid());
        assertEquals(3, loops.get(1).count());

        List<DeathLoop> tied = DeathLoopDetector.findLoopsFromSamples(List.of(
                sample("b", T0 + MINUTE, 0, 0),
                sample("b", T0 + 2 * MINUTE, 1, 0),
                sample("b", T0 + 3 * MINUTE, 2, 0),
                sample("a", T0, 0, 0),
                sample("a", T0 + MINUTE, 1, 0),
                sample("a", T0 + 2 * MINUTE, 2, 0)), 48, 10 * MINUTE, 3);
        assertEquals(2, tied.size());
        assertEquals("a", tied.get(0).uuid());
    }

    @Test
    void centerRoundsAndTopCauseUsesCountThenAlphabeticalTieBreak() {
        List<DeathLoop> loops = DeathLoopDetector.findLoopsFromSamples(List.of(
                sample("u", T0, 0.4, -1.6, OVERWORLD, "zombie"),
                sample("u", T0 + MINUTE, 0.5, -1.5, OVERWORLD, "creeper"),
                sample("u", T0 + 2 * MINUTE, 0.6, -1.4, OVERWORLD, "zombie")),
                48, 10 * MINUTE, 3);
        assertEquals(1, loops.size());
        assertEquals(1, loops.get(0).centerX());
        // Math.round(-1.5) is -1 (half rounds toward positive infinity).
        assertEquals(-1, loops.get(0).centerZ());
        assertEquals("zombie", loops.get(0).topCause());

        DeathLoop tied = DeathLoopDetector.findLoopsFromSamples(List.of(
                sample("u", T0, 0, 0, OVERWORLD, "zombie"),
                sample("u", T0 + MINUTE, 1, 0, OVERWORLD, "creeper"),
                sample("u", T0 + 2 * MINUTE, 2, 0, OVERWORLD, "creeper")),
                48, 10 * MINUTE, 3).get(0);
        assertEquals("creeper", tied.topCause());
    }

    @Test
    void publicViewExcludesOptedOutHiddenCoordinateAndSentinelDeaths() {
        DeathEntry sentinel = death(T0, 5, 5, "fall");
        sentinel = new DeathEntry(sentinel.playerUuid(), sentinel.playerName(),
                sentinel.epochMillis(), sentinel.gameDay(), sentinel.playTicks(),
                sentinel.survivalTicks(), sentinel.dimension(),
                Double.NaN, Double.NaN, Double.NaN, sentinel.causeCategory(),
                sentinel.causeId(), sentinel.deathMessage(), sentinel.killerType(),
                sentinel.killerName(), sentinel.gameMode(), sentinel.experienceLevel(),
                sentinel.excuse(), sentinel.lastWords(), sentinel.fCount());
        List<PlayerDeaths> players = List.of(
                player("hidden", false, true, death(T0, 0, 0, "fall"),
                        death(T0 + MINUTE, 1, 0, "fall"), death(T0 + 2 * MINUTE, 2, 0, "fall")),
                player("optout", true, false, death(T0, 10, 10, "fall"),
                        death(T0 + MINUTE, 11, 10, "fall"), death(T0 + 2 * MINUTE, 12, 10, "fall")),
                player("sentinel", false, false, sentinel, death(T0 + MINUTE, 6, 5, "fall"),
                        death(T0 + 2 * MINUTE, 7, 5, "fall")),
                player("public", false, false, death(T0, 20, 20, "fall"),
                        death(T0 + MINUTE, 21, 20, "fall"), death(T0 + 2 * MINUTE, 22, 20, "fall")));
        List<DeathLoop> loops = DeathLoopDetector.findLoops(players, 48, 10 * MINUTE, 3);
        assertEquals(1, loops.size());
        assertEquals("public", loops.get(0).uuid());
        assertEquals(21, loops.get(0).centerX());
        // Anonymous totals remain computable from the unredacted snapshot.
        assertEquals(12, CensusStats.totalDeaths(players));
    }

    @Test
    void selfAdminViewCanIncludeFlaggedRecordsWhilePublicViewCannot() {
        List<PlayerDeaths> players = List.of(player("hidden", true, true,
                death(T0, 0, 0, "fall"), death(T0 + MINUTE, 1, 0, "fall"),
                death(T0 + 2 * MINUTE, 2, 0, "fall")));
        assertTrue(DeathLoopDetector.findLoops(players, 48, 10 * MINUTE, 3).isEmpty());
        List<DeathLoop> privateRows = DeathLoopDetector.findLoops(players, 48, 10 * MINUTE, 3, true);
        assertEquals(1, privateRows.size());
        assertEquals("hidden", privateRows.get(0).uuid());
    }

    @Test
    void hidePvpOpponentDoesNotChangeDetection() {
        PlayerDeaths player = new PlayerDeaths("u", "Victim", false, false, true, false,
                List.of(death(T0, 0, 0, "player"), death(T0 + MINUTE, 1, 0, "player"),
                        death(T0 + 2 * MINUTE, 2, 0, "player")));
        DeathLoop loop = DeathLoopDetector.findLoops(List.of(player), 48, 10 * MINUTE, 3).get(0);
        assertEquals("player", loop.topCause());
    }

    @Test
    void snapshotOverloadMatchesRawSampleOverloadAndDoesNotMutateInput() {
        DeathEntry first = death(T0 + 2 * MINUTE, 2, 0, "fall");
        DeathEntry second = death(T0, 0, 0, "fall");
        DeathEntry third = death(T0 + MINUTE, 1, 0, "fall");
        List<DeathEntry> entries = new ArrayList<>(List.of(first, second, third));
        List<PlayerDeaths> players = List.of(player("u", false, false,
                entries.toArray(new DeathEntry[0])));
        DeathLoop loop = DeathLoopDetector.findLoops(players, 48, 10 * MINUTE, 3).get(0);
        assertEquals(T0, loop.firstEpoch());
        assertEquals(3, loop.count());
        assertEquals(List.of(first, second, third), entries);
        assertEquals(loop, DeathLoopDetector.findLoopsFromSamples(List.of(
                DeathLoopSample.of("u", "Player-u", second),
                DeathLoopSample.of("u", "Player-u", third),
                DeathLoopSample.of("u", "Player-u", first)), 48, 10 * MINUTE, 3).get(0));
    }

    @Test
    void nullEmptyAndInvalidInputIsSafe() {
        assertTrue(DeathLoopDetector.findLoopsFromSamples(null, 48, 10 * MINUTE, 3).isEmpty());
        assertTrue(DeathLoopDetector.findLoops((List<PlayerDeaths>) null).isEmpty());
        assertTrue(DeathLoopDetector.findLoopsFromSamples(List.of()).isEmpty());
        assertTrue(DeathLoopDetector.findLoopsFromSamples(List.of(
                new DeathLoopSample("u", "u", T0, Double.NaN, 1, 0, OVERWORLD, "fall"),
                sample("u", T0 + MINUTE, 1, 0),
                sample("u", T0 + 2 * MINUTE, 2, 0)), 48, 10 * MINUTE, 3).isEmpty());
    }

    @Test
    void trackerNotifiesOncePerKeyAndAgainWhenTheRunGrows() {
        DeathLoopTracker tracker = new DeathLoopTracker();
        assertTrue(tracker.observe(sample("u", T0, 0, 0)).isEmpty());
        assertTrue(tracker.observe(sample("u", T0 + MINUTE, 1, 0)).isEmpty());
        List<DeathLoop> first = tracker.observe(sample("u", T0 + 2 * MINUTE, 2, 0));
        assertEquals(1, first.size());
        assertEquals(3, first.get(0).count());
        assertTrue(tracker.wasAnnounced(first.get(0)));
        assertFalse(tracker.shouldAnnounce(first.get(0)));
        assertEquals(1, tracker.announcedCount());
        List<DeathLoop> grown = tracker.observe(sample("u", T0 + 3 * MINUTE, 3, 0));
        assertEquals(1, grown.size());
        assertEquals(4, grown.get(0).count());
        assertNotEquals(first.get(0).noticeKey(), grown.get(0).noticeKey());
        assertEquals(2, tracker.announcedCount());
    }

    @Test
    void trackerRingKeepsOnlyTheLastSixteenSamples() {
        DeathLoopTracker tracker = new DeathLoopTracker();
        for (int i = 0; i < 20; i++) {
            tracker.observe(sample("u", T0 + i * MINUTE, i * 100.0, 0));
        }
        assertEquals(16, tracker.ring("u").size());
        assertEquals(T0 + 4 * MINUTE, tracker.ring("u").get(0).epochMillis());
        assertEquals(T0 + 19 * MINUTE, tracker.ring("u").get(15).epochMillis());
        assertTrue(tracker.announcedCount() == 0);
        assertEquals(1, tracker.ringCount());
    }

    @Test
    void trackerPrunesDedupeKeysAtOneThousand() {
        DeathLoopTracker tracker = new DeathLoopTracker();
        DeathLoop last = null;
        for (int i = 0; i < 1005; i++) {
            List<DeathLoop> fresh = tracker.observe(sample("u" + i, T0 + i, 0, 0),
                    48, 10 * MINUTE, 2);
            assertTrue(fresh.isEmpty());
            fresh = tracker.observe(sample("u" + i, T0 + i + 1, 1, 0), 48, 10 * MINUTE, 2);
            assertEquals(1, fresh.size());
            last = fresh.get(0);
        }
        assertEquals(DeathLoopTracker.MAX_ANNOUNCED_KEYS, tracker.announcedCount());
        assertTrue(tracker.wasAnnounced(last));
    }

    @Test
    void trackerClearResetsRingsAndDedupe() {
        DeathLoopTracker tracker = new DeathLoopTracker();
        tracker.observe(sample("u", T0, 0, 0));
        tracker.observe(sample("u", T0 + MINUTE, 1, 0));
        DeathLoop loop = tracker.observe(sample("u", T0 + 2 * MINUTE, 2, 0)).get(0);
        assertEquals(1, tracker.ringCount());
        assertTrue(tracker.wasAnnounced(loop));
        tracker.clear();
        assertEquals(0, tracker.ringCount());
        assertEquals(0, tracker.announcedCount());
        assertTrue(tracker.ring("u").isEmpty());
        assertFalse(tracker.wasAnnounced(loop));
        assertTrue(tracker.observe(sample("u", T0, 0, 0)).isEmpty());
        assertTrue(tracker.observe(sample("u", T0 + MINUTE, 1, 0)).isEmpty());
        List<DeathLoop> again = tracker.observe(sample("u", T0 + 2 * MINUTE, 2, 0));
        assertEquals(1, again.size());
        assertEquals(loop.noticeKey(), again.get(0).noticeKey());
    }

    @Test
    void trackerIgnoresInvalidSamplesAndHonoursConfiguredThresholds() {
        DeathLoopTracker tracker = new DeathLoopTracker();
        assertTrue(tracker.observe(null).isEmpty());
        assertTrue(tracker.observe(new DeathLoopSample("u", "u", T0,
                Double.POSITIVE_INFINITY, 1, 0, OVERWORLD, "fall")).isEmpty());
        assertEquals(0, tracker.ringCount());

        // 20-block gaps exceed an 8-block radius, so no loop forms.
        assertTrue(tracker.observe(sample("u", T0, 0, 0), 8, 10 * MINUTE, 3).isEmpty());
        assertTrue(tracker.observe(sample("u", T0 + MINUTE, 20, 0), 8, 10 * MINUTE, 3).isEmpty());
        assertTrue(tracker.observe(sample("u", T0 + 2 * MINUTE, 40, 0), 8, 10 * MINUTE, 3).isEmpty());
        // The same three samples do form a loop with the configured 48-block radius.
        DeathLoopTracker lenient = new DeathLoopTracker();
        lenient.observe(sample("u", T0, 0, 0), 48, 10 * MINUTE, 3);
        lenient.observe(sample("u", T0 + MINUTE, 20, 0), 48, 10 * MINUTE, 3);
        assertEquals(1, lenient.observe(sample("u", T0 + 2 * MINUTE, 40, 0),
                48, 10 * MINUTE, 3).size());
        // Two-minute gaps exceed a one-minute window.
        DeathLoopTracker strict = new DeathLoopTracker();
        strict.observe(sample("u", T0, 0, 0), 48, MINUTE, 3);
        strict.observe(sample("u", T0 + 2 * MINUTE, 1, 0), 48, MINUTE, 3);
        assertTrue(strict.observe(sample("u", T0 + 4 * MINUTE, 2, 0), 48, MINUTE, 3).isEmpty());
    }
}
