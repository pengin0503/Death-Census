package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN 1.4.0 §9: pure guide math, expiry, refresh throttle and self-only state. */
class RespawnGuideTextTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final UUID VICTIM = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void eightCompassPointsIncludeExactNorthAndEastBoundaries() {
        assertEquals("N", RespawnGuideText.compass(0.0, -10.0));
        assertEquals("NE", RespawnGuideText.compass(10.0, -10.0));
        assertEquals("E", RespawnGuideText.compass(10.0, 0.0));
        assertEquals("SE", RespawnGuideText.compass(10.0, 10.0));
        assertEquals("S", RespawnGuideText.compass(0.0, 10.0));
        assertEquals("SW", RespawnGuideText.compass(-10.0, 10.0));
        assertEquals("W", RespawnGuideText.compass(-10.0, 0.0));
        assertEquals("NW", RespawnGuideText.compass(-10.0, -10.0));
    }

    @Test
    void compassSectorsAreFortyFiveDegreesAndZeroDeltaIsNorth() {
        assertEquals("N", RespawnGuideText.compass(0.0, 0.0));
        assertEquals("N", RespawnGuideText.compass(1.0, -10.0));
        assertEquals("NE", RespawnGuideText.compass(10.0, -24.0));
        assertEquals("E", RespawnGuideText.compass(24.0, -1.0));
        assertEquals("NW", RespawnGuideText.compass(-24.0, -10.0));
    }

    @Test
    void horizontalDistanceRoundsTheXZDistanceAndIgnoresHeight() {
        assertEquals(5, RespawnGuideText.horizontalDistance(3.0, 4.0));
        assertEquals(0, RespawnGuideText.horizontalDistance(0.49, 0.0));
        assertEquals(1, RespawnGuideText.horizontalDistance(0.5, 0.0));
        assertEquals(10, RespawnGuideText.horizontalDistance(0.0, -10.0));
        assertEquals(10, RespawnGuideText.horizontalDistance(6.0, -8.0));
    }

    @Test
    void heightDifferenceIsSignedFromThePlayerTowardTheDeathPoint() {
        assertEquals(6, RespawnGuideText.heightDifference(70.0, 64.0));
        assertEquals(-4, RespawnGuideText.heightDifference(60.0, 64.0));
        assertEquals(0, RespawnGuideText.heightDifference(64.0, 64.0));
        assertEquals("+6", RespawnGuideText.signedHeight(6));
        assertEquals("-4", RespawnGuideText.signedHeight(-4));
        assertEquals("0", RespawnGuideText.signedHeight(0));
    }

    @Test
    void remainingTimeFormatsFullAndExpiredLifetimes() {
        assertEquals("5m 0s", RespawnGuideText.remainingTime(6000L));
        assertEquals("0s", RespawnGuideText.remainingTime(0L));
        assertEquals("0s", RespawnGuideText.remainingTime(-20L));
        assertEquals("1s", RespawnGuideText.remainingTime(20L));
    }

    @Test
    void arrivalUsesExactSquaredHorizontalDistance() {
        assertTrue(RespawnGuideText.arrived(0.0, 0.0, 16));
        assertTrue(RespawnGuideText.arrived(16.0, 0.0, 16));
        assertTrue(RespawnGuideText.arrived(-16.0, 0.0, 16));
        assertFalse(RespawnGuideText.arrived(16.01, 0.0, 16));
        assertFalse(RespawnGuideText.arrived(12.0, 12.0, 16));
        assertTrue(RespawnGuideText.arrived(0.0, 0.0, 0));
    }

    @Test
    void stateRecordsDeathValuesAndExpiresAtTheConfiguredTick() {
        long tick = 10_000L;
        RespawnGuideState state = RespawnGuideState.atDeath(VICTIM, OVERWORLD, 10.0, 70.0,
                -4.0, 1234L, tick, RespawnGuideText.DEFAULT_MAX_TICKS);
        assertEquals(VICTIM, state.uuid());
        assertEquals(OVERWORLD, state.dimension());
        assertEquals(10.0, state.x());
        assertEquals(70.0, state.y());
        assertEquals(-4.0, state.z());
        assertEquals(1234L, state.deathEpochMillis());
        assertEquals(tick + 6000L, state.expiresAtTick());
        assertEquals(6000L, state.remainingTicks(tick));
        assertFalse(state.expired(tick + 5999L));
        assertTrue(state.expired(tick + 6000L));
        assertEquals(0L, state.remainingTicks(tick + 6001L));
    }

    @Test
    void dimensionMismatchSuppressesBearingData() {
        RespawnGuideState state = RespawnGuideState.atDeath(VICTIM, OVERWORLD, 1.0, 2.0, 3.0,
                1L, 0L, 100L);
        assertTrue(state.sameDimension(OVERWORLD));
        assertFalse(state.sameDimension("minecraft:the_nether"));
        assertFalse(state.sameDimension(null));
    }

    @Test
    void refreshIsThrottledUntilTheConfiguredIntervalElapsed() {
        RespawnGuideState state = RespawnGuideState.atDeath(VICTIM, OVERWORLD, 0.0, 0.0, 0.0,
                1L, 0L, 6000L);
        assertFalse(state.dueForRefresh(100L, 20L));
        state.markShown(100L);
        assertTrue(state.shown());
        assertFalse(state.dueForRefresh(119L, 20L));
        assertTrue(state.dueForRefresh(120L, 20L));
        state.markRefreshed(120L);
        assertFalse(state.dueForRefresh(139L, 20L));
        assertTrue(state.dueForRefresh(140L, 20L));
    }

    @Test
    void trackerIsKeyedByVictimOnlyAndCanBeCleared() {
        RespawnGuideTracker tracker = new RespawnGuideTracker();
        RespawnGuideState state = tracker.begin(VICTIM, OVERWORLD, 1.0, 2.0, 3.0,
                1L, 0L, 6000L);
        assertEquals(1, tracker.size());
        assertEquals(state, tracker.get(VICTIM));
        assertNull(tracker.get(OTHER));
        assertNull(tracker.get(null));
        assertNull(tracker.remove(OTHER));
        assertEquals(state, tracker.remove(VICTIM));
        assertNull(tracker.get(VICTIM));
        tracker.begin(OTHER, OVERWORLD, 1.0, 2.0, 3.0, 2L, 0L, 6000L);
        tracker.clear();
        assertEquals(0, tracker.size());
    }

    @Test
    void secondDeathReplacesThePreviousGuide() {
        RespawnGuideTracker tracker = new RespawnGuideTracker();
        RespawnGuideState first = tracker.begin(VICTIM, OVERWORLD, 1.0, 2.0, 3.0, 1L, 0L, 6000L);
        RespawnGuideState second = tracker.begin(VICTIM, OVERWORLD, 9.0, 8.0, 7.0, 2L, 20L, 6000L);
        assertNotSame(first, second);
        assertEquals(9.0, tracker.get(VICTIM).x());
        assertEquals(2L, tracker.get(VICTIM).deathEpochMillis());
        assertEquals(1, tracker.size());
    }

    @Test
    void expiredGuidesArePrunedAndTheMapIsBounded() {
        RespawnGuideTracker tracker = new RespawnGuideTracker();
        tracker.begin(VICTIM, OVERWORLD, 1.0, 2.0, 3.0, 1L, 0L, 100L);
        tracker.begin(OTHER, OVERWORLD, 1.0, 2.0, 3.0, 2L, 0L, 6000L);
        assertEquals(1, tracker.pruneExpired(100L));
        assertNull(tracker.get(VICTIM));
        assertEquals(1, tracker.size());
        for (int i = 0; i < RespawnGuideTracker.MAX_TRACKED_PLAYERS + 5; i++) {
            tracker.begin(new UUID(0L, i + 10L), OVERWORLD, 0.0, 0.0, 0.0,
                    i, 0L, 6000L);
        }
        assertEquals(RespawnGuideTracker.MAX_TRACKED_PLAYERS, tracker.size());
        assertNull(tracker.get(new UUID(0L, 10L)));
    }
}
