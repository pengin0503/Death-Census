package com.pengin0503.deathcensus.ui;

import com.pengin0503.deathcensus.DeathEntry;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryFilterTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final long NOW = 1_700_000_000_000L; // 2023-11-14T12:00:00Z
    private static final long DAY = 86_400_000L;

    @Test
    void periodCutoffsMirrorCensusCommandsSinceMillis() {
        HistoryFilter today = new HistoryFilter("today", "all", "all", "");
        long startOfDay = NOW - 80_000_000L; // 2023-11-14T00:00:00Z
        assertEquals(startOfDay, today.sinceMillis(NOW, UTC));
        assertEquals(NOW - 7L * DAY, new HistoryFilter("7d", "all", "all", "").sinceMillis(NOW, UTC));
        assertEquals(NOW - 30L * DAY, new HistoryFilter("30d", "all", "all", "").sinceMillis(NOW, UTC));
        assertEquals(0L, new HistoryFilter("all", "all", "all", "").sinceMillis(NOW, UTC));

        DeathEntry death = TestData.death(TestData.ALICE, "Alice", startOfDay + 1L, "fall");
        assertTrue(today.matches(death, NOW, UTC));
        assertFalse(today.matches(TestData.death(TestData.ALICE, "Alice", startOfDay - 1L, "fall"), NOW, UTC));
        HistoryFilter week = new HistoryFilter("7d", "all", "all", "");
        assertTrue(week.matches(TestData.death(TestData.ALICE, "Alice", NOW - 6L * DAY, "fall"), NOW, UTC));
        assertFalse(week.matches(TestData.death(TestData.ALICE, "Alice", NOW - 8L * DAY, "fall"), NOW, UTC));
        assertTrue(new HistoryFilter("all", "all", "all", "")
                .matches(TestData.death(TestData.ALICE, "Alice", 0L, "fall"), NOW, UTC));
    }

    @Test
    void categoryDimensionAndQueryUseCensusCommandsSemantics() {
        DeathEntry death = TestData.death(
                TestData.ALICE, "Alice", NOW, "fall", "minecraft:the_nether", "Alice fell hard");
        assertTrue(new HistoryFilter("all", "fall", "all", "").matches(death, NOW, UTC));
        assertFalse(new HistoryFilter("all", "drown", "all", "").matches(death, NOW, UTC));
        assertTrue(new HistoryFilter("all", "all", "minecraft:the_nether", "").matches(death, NOW, UTC));
        assertFalse(new HistoryFilter("all", "all", "minecraft:overworld", "").matches(death, NOW, UTC));
        assertTrue(new HistoryFilter("all", "fall", "minecraft:the_nether", "").matches(death, NOW, UTC));
        assertFalse(new HistoryFilter("all", "fall", "minecraft:overworld", "").matches(death, NOW, UTC));
        assertTrue(new HistoryFilter("all", "all", "all", "FELL").matches(death, NOW, UTC));
        assertTrue(new HistoryFilter("all", "all", "all", "alice").matches(death, NOW, UTC));
        assertFalse(new HistoryFilter("all", "all", "all", "nobody").matches(death, NOW, UTC));
    }

    @Test
    void inputsAreNormalizedAndQueryIsBounded() {
        String longQuery = "q".repeat(70);
        HistoryFilter filter = new HistoryFilter("BOGUS", " Fall ", "", "  " + longQuery + "  ");
        assertEquals("all", filter.period());
        assertEquals("fall", filter.category());
        assertEquals("all", filter.dimension());
        assertEquals(HistoryFilter.MAX_QUERY_LENGTH, filter.query().length());
        assertEquals(filter, filter.normalize());
        assertEquals("", HistoryFilter.cleanQuery(null));
        assertEquals(4, HistoryFilter.PERIODS.size());
    }
}
