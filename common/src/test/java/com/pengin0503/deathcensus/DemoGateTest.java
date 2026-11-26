package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN 1.3.0 §8 / §12 Q20: both gates are required, neither is enough. */
class DemoGateTest {

    @Test
    void onlyBothGatesEnable() {
        assertFalse(DemoGate.enabled(false, null));
        assertFalse(DemoGate.enabled(false, "true"));
        assertFalse(DemoGate.enabled(true, null));
        assertFalse(DemoGate.enabled(true, "false"));
        assertTrue(DemoGate.enabled(true, "true"));
    }

    @Test
    void systemPropertyIsParsedDefensively() {
        assertTrue(DemoGate.enabled(true, " TRUE "));
        assertFalse(DemoGate.enabled(true, ""));
        assertFalse(DemoGate.enabled(true, "   "));
        assertFalse(DemoGate.enabled(true, "1"));
        assertFalse(DemoGate.enabled(true, "yes"));
        assertFalse(DemoGate.enabled(false, " true "));
    }

    @Test
    void propertyOverloadReadsProcessWideFlag() {
        String previous = System.getProperty(DemoGate.SYSTEM_PROPERTY);
        try {
            System.clearProperty(DemoGate.SYSTEM_PROPERTY);
            assertFalse(DemoGate.enabled(true));
            System.setProperty(DemoGate.SYSTEM_PROPERTY, "true");
            assertTrue(DemoGate.enabled(true));
            System.setProperty(DemoGate.SYSTEM_PROPERTY, "false");
            assertFalse(DemoGate.enabled(true));
        } finally {
            if (previous == null) {
                System.clearProperty(DemoGate.SYSTEM_PROPERTY);
            } else {
                System.setProperty(DemoGate.SYSTEM_PROPERTY, previous);
            }
        }
    }
}
