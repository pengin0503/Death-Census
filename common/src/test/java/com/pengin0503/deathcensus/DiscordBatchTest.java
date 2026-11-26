package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN 1.3.0 §2 test plan: pure batch composition, no network and no waiting. */
class DiscordBatchTest {
    private static final String HEADER = "3 deaths in the last 1 seconds";
    private static final String MORE = "…and 2 more";

    @Test
    void emptyRenderedLinesComposeToEmptyString() {
        assertEquals("", DiscordBatch.content(null, 0, HEADER, MORE, 2000));
        assertEquals("", DiscordBatch.content(List.of(), 0, HEADER, MORE, 2000));
        assertEquals("", DiscordBatch.content(Arrays.asList(null, "  ", ""), 3, HEADER, MORE, 2000));
    }

    @Test
    void singleDeathPassesThroughUnchanged() {
        String line = "Alice fell — Day 1 · minecraft:overworld · survived 12s";
        // Even when a header and suffix are supplied (version code formats them
        // before it knows the buffer size), one death renders exactly as today.
        assertEquals(line, DiscordBatch.content(List.of(line), 1, HEADER, MORE, 2000));
        // Passthrough ignores maxChars: payloadJson is the existing 2000-char cap.
        assertEquals(line, DiscordBatch.content(List.of(line), 1, HEADER, MORE, 5));
    }

    @Test
    void threeLinesComposeHeaderThenChronologicalLines() {
        assertEquals(HEADER + "\none\ntwo\nthree",
                DiscordBatch.content(List.of("one", "two", "three"), 3, HEADER, "", 2000));
        // Blank/null lines are skipped without disturbing the surviving order.
        assertEquals(HEADER + "\none\nthree",
                DiscordBatch.content(Arrays.asList("one", null, "  ", "three"), 3, HEADER, "", 2000));
    }

    @Test
    void capKeepsFirstLinesAndAppendsMoreSuffix() {
        // Version code caps the rendered list; the composer sees 3 of 5 deaths.
        assertEquals(HEADER + "\none\ntwo\nthree\n" + MORE,
                DiscordBatch.content(List.of("one", "two", "three"), 5, HEADER, MORE, 2000));
        // A cap of one still composes a batch when more than one death was buffered.
        String header5 = "5 deaths in the last 1 seconds";
        assertEquals(header5 + "\none\n" + MORE,
                DiscordBatch.content(List.of("one"), 5, header5, MORE, 2000));
        // No suffix when the buffer held no more deaths than the shown lines.
        assertEquals(HEADER + "\none\ntwo\nthree",
                DiscordBatch.content(List.of("one", "two", "three"), 3, HEADER, MORE, 2000));
    }

    @Test
    void longLinesComposeWithinDiscordLimit() {
        String composed = DiscordBatch.content(
                List.of("a".repeat(900), "b".repeat(900), "c".repeat(900)), 3, HEADER, "", 2000);
        assertTrue(composed.length() <= 2000);
        assertEquals(2000, composed.length());
        assertTrue(composed.startsWith(HEADER));
    }

    @Test
    void moreSuffixSurvivesTruncationWherePossible() {
        String composed = DiscordBatch.content(
                List.of("x".repeat(1500), "y".repeat(1500)), 12, HEADER, MORE, 2000);
        assertEquals(2000, composed.length());
        assertTrue(composed.endsWith(MORE));
        // With no room for the suffix, truncation is still hard-bounded.
        assertEquals(4, DiscordBatch.content(
                List.of("x".repeat(50), "y".repeat(50)), 12, HEADER, MORE, 4).length());
    }

    @Test
    void maxCharsCanTightenTheLimitBelowTheDiscordCap() {
        String composed = DiscordBatch.content(List.of("one", "two", "three"), 3, HEADER, "", 12);
        assertEquals(12, composed.length());
        assertEquals(HEADER.substring(0, 12), composed);
        assertEquals(0, DiscordBatch.content(List.of("one", "two"), 2, HEADER, "", 0).length());
        assertEquals(0, DiscordBatch.content(List.of("one", "two"), 2, HEADER, "", -5).length());
    }

    @Test
    void nullHeaderAndSuffixAreSafe() {
        assertEquals("one\ntwo", DiscordBatch.content(List.of("one", "two"), 2, null, null, 2000));
        assertEquals(HEADER + "\none\ntwo",
                DiscordBatch.content(List.of("one", "two"), 2, HEADER, null, 2000));
    }
}
