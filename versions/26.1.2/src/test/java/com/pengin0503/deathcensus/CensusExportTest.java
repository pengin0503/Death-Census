package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Filename rule for anonymous exports (DESIGN 1.3.0 §6). */
class CensusExportTest {
    @Test
    void anonymizedFileNamesHaveSuffixAndNeverCarryAPlayerName() {
        for (String format : List.of("html", "json", "csv")) {
            String normal = CensusExport.exportFileName(format, "Alice", false, ZoneOffset.UTC);
            assertTrue(normal.startsWith("deathcensus-"));
            assertTrue(normal.contains("-Alice." + format));
            assertFalse(normal.contains("-anonymized"));

            String anonymized = CensusExport.exportFileName(format, "Alice", true, ZoneOffset.UTC);
            assertTrue(anonymized.matches("deathcensus-\\d{8}-\\d{6}-anonymized\\." + format), anonymized);
            assertFalse(anonymized.contains("Alice"), anonymized);

            String noPlayer = CensusExport.exportFileName(format, null, true, ZoneOffset.UTC);
            assertTrue(noPlayer.matches("deathcensus-\\d{8}-\\d{6}-anonymized\\." + format), noPlayer);
        }
    }

    @Test
    void normalFileNameStillSanitizesPlayerNames() {
        assertEquals("Alice_Smith", CensusExport.sanitizeFilePart("Alice Smith"));
        String name = CensusExport.exportFileName("json", "Alice Smith", false, ZoneOffset.UTC);
        assertTrue(name.contains("-Alice_Smith.json"), name);
        assertFalse(name.contains(" "), name);
    }
}
