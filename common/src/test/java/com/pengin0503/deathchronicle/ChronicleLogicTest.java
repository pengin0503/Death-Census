package com.pengin0503.deathchronicle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChronicleLogicTest {

    @Test
    void survivalTicksUsesLastDeathOrBaselineAndClampsToZero() {
        // First death (lastDeathPlayTicks < 0): uses baseline
        assertEquals(500, ChronicleLogic.survivalTicks(1500, -1, 1000));
        assertEquals(1500, ChronicleLogic.survivalTicks(1500, -1, -200));
        assertEquals(0, ChronicleLogic.survivalTicks(800, -1, 1000));

        // Subsequent death (lastDeathPlayTicks >= 0): ignores baseline
        assertEquals(300, ChronicleLogic.survivalTicks(2300, 2000, 100));
        assertEquals(0, ChronicleLogic.survivalTicks(1800, 2000, 100));
        assertEquals(50, ChronicleLogic.survivalTicks(50, 0, 100));
    }

    @Test
    void excessCountClampsMaxAndResult() {
        assertEquals(0, ChronicleLogic.excessCount(0, 10));
        assertEquals(0, ChronicleLogic.excessCount(10, 10));
        assertEquals(5, ChronicleLogic.excessCount(15, 10));
        assertEquals(4, ChronicleLogic.excessCount(5, 0));
        assertEquals(4, ChronicleLogic.excessCount(5, -3));
        assertEquals(0, ChronicleLogic.excessCount(1, 0));
    }
}
