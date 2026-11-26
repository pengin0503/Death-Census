package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeasonRangeTest {

    private static long epoch(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    @Test
    void isoDatesResolveToStartOfDayInUtc() {
        SeasonRange.ParseResult result = SeasonRange.parse("2024-01-01", "2024-01-03", ZoneOffset.UTC);

        assertTrue(result.valid());
        assertNull(result.failure());
        assertEquals(new SeasonRange(epoch("2024-01-01T00:00:00Z"), epoch("2024-01-03T00:00:00Z")),
                result.range());
        assertTrue(result.range().contains(epoch("2024-01-01T00:00:00Z")));
        assertTrue(result.range().contains(epoch("2024-01-03T00:00:00Z")));
        assertFalse(result.range().contains(epoch("2023-12-31T23:59:59Z")));
        assertFalse(result.range().contains(epoch("2024-01-03T00:00:01Z")));
    }

    @Test
    void isoDatesResolveToStartOfDayInConfiguredZone() {
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        SeasonRange.ParseResult result = SeasonRange.parse("2024-01-01", "2024-01-02", tokyo);

        assertTrue(result.valid());
        assertEquals(epoch("2023-12-31T15:00:00Z"), result.range().fromMillisInclusive());
        assertEquals(epoch("2024-01-01T15:00:00Z"), result.range().toMillisInclusive());

        SeasonRange.ParseResult utc = SeasonRange.parse("2024-01-01", "2024-01-02", ZoneOffset.UTC);
        assertTrue(result.range().fromMillisInclusive() < utc.range().fromMillisInclusive());
    }

    @Test
    void epochFormIsExactAndSupportsNegativeValues() {
        SeasonRange.ParseResult result = SeasonRange.parse("epoch:0", "epoch:1000", ZoneOffset.UTC);
        assertTrue(result.valid());
        assertEquals(new SeasonRange(0L, 1000L), result.range());

        SeasonRange.ParseResult negative = SeasonRange.parse("epoch:-2000", "epoch:-1", null);
        assertTrue(negative.valid());
        assertEquals(new SeasonRange(-2000L, -1L), negative.range());
    }

    @Test
    void invertedAndEqualBoundsAreRejected() {
        assertEquals(SeasonRange.Failure.INVERTED,
                SeasonRange.parse("2024-01-03", "2024-01-01", ZoneOffset.UTC).failure());
        assertEquals(SeasonRange.Failure.INVERTED,
                SeasonRange.parse("2024-01-01", "2024-01-01", ZoneOffset.UTC).failure());
        assertEquals(SeasonRange.Failure.INVERTED,
                SeasonRange.parse("epoch:500", "epoch:499", ZoneOffset.UTC).failure());
        assertTrue(SeasonRange.parse("epoch:1", "epoch:2", ZoneOffset.UTC).valid()); // sanity: valid path
    }

    @Test
    void malformedInputsReportBadFormat() {
        String[] bad = {null, "", "  ", "2024/01/01", "2024-1-01", "2024-01-01T00:00",
                "epoch:", "epoch:abc", "epoch:1.5", " 2024-01-01", "2024-01-01 "};
        for (String input : bad) {
            assertEquals(SeasonRange.Failure.BAD_FORMAT,
                    SeasonRange.parse(input, "epoch:1", ZoneOffset.UTC).failure(), "from=" + input);
            assertEquals(SeasonRange.Failure.BAD_FORMAT,
                    SeasonRange.parse("epoch:0", input, ZoneOffset.UTC).failure(), "to=" + input);
        }
    }

    @Test
    void impossibleDatesReportBadDateAndLeapDayIsValid() {
        assertEquals(SeasonRange.Failure.BAD_DATE,
                SeasonRange.parse("2023-02-29", "2024-01-01", ZoneOffset.UTC).failure());
        assertEquals(SeasonRange.Failure.BAD_DATE,
                SeasonRange.parse("2024-13-01", "2024-14-01", ZoneOffset.UTC).failure());
        assertEquals(SeasonRange.Failure.BAD_DATE,
                SeasonRange.parse("2024-00-10", "epoch:1", ZoneOffset.UTC).failure());
        assertTrue(SeasonRange.parse("2024-02-29", "2024-03-01", ZoneOffset.UTC).valid());
    }

    @Test
    void failureResultsCarryNoRange() {
        SeasonRange.ParseResult failure = SeasonRange.parse("epoch:9", "epoch:1", ZoneOffset.UTC);
        assertFalse(failure.valid());
        assertNull(failure.range());
        assertEquals(SeasonRange.Failure.INVERTED, failure.failure());
    }

    @Test
    void fileNameStampUsesCompactDatesInDisplayZone() {
        SeasonRange range = SeasonRange.parse("2024-01-01", "2024-02-29", ZoneOffset.UTC).range();
        assertEquals("20240101-20240229", range.fileNameStamp(ZoneOffset.UTC));
        assertEquals("20240101-20240229", range.fileNameStamp(ZoneId.of("Asia/Tokyo")));
        assertEquals("20231231-20240228", range.fileNameStamp(ZoneId.of("America/Los_Angeles")));
    }
}
