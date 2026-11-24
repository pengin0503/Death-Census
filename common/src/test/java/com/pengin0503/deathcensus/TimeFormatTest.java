package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeFormatTest {

    @Test
    void formatDurationCoversSecondsMinutesHoursAndNegatives() {
        assertEquals("0s", TimeFormat.formatDuration(-100));
        assertEquals("0s", TimeFormat.formatDuration(0));
        assertEquals("0s", TimeFormat.formatDuration(19));
        assertEquals("1s", TimeFormat.formatDuration(20));
        assertEquals("59s", TimeFormat.formatDuration(59 * 20));
        assertEquals("1m 0s", TimeFormat.formatDuration(60 * 20));
        assertEquals("15m 30s", TimeFormat.formatDuration((15 * 60 + 30) * 20));
        assertEquals("1h 0m 0s", TimeFormat.formatDuration(3600 * 20));
        assertEquals("27h 15m 30s", TimeFormat.formatDuration((27 * 3600 + 15 * 60 + 30) * 20));
    }

    @Test
    void formatDateTimeAndDateRespectZone() {
        long epoch = Instant.parse("2026-11-24T21:03:45Z").toEpochMilli();
        assertEquals("2026-11-24 21:03", TimeFormat.formatDateTime(epoch, ZoneOffset.UTC));
        assertEquals("2026-11-25 06:03", TimeFormat.formatDateTime(epoch, ZoneId.of("Asia/Tokyo")));
        assertEquals("2026-11-24", TimeFormat.formatDate(epoch, ZoneOffset.UTC));
        assertEquals("2026-11-25", TimeFormat.formatDate(epoch, ZoneId.of("Asia/Tokyo")));

        // Null zone falls back to systemDefault without throwing
        assertTrue(TimeFormat.formatDateTime(epoch, null).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"));
        assertTrue(TimeFormat.formatDate(epoch, null).matches("\\d{4}-\\d{2}-\\d{2}"));
    }
}
