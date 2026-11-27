package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backup parser tolerance (S10) and safety-backup naming/collision handling (M4). */
class CensusExportTest {
    private static final String U1 = "00000000-0000-0000-0000-000000000001";
    private static final String U2 = "00000000-0000-0000-0000-000000000002";

    @Test
    void backupJsonSkipsCorruptElementsAndKeepsValidSiblings() {
        String json = """
                {"players":[
                 {"uuid":"%s","name":"Good","optOutPublic":true,
                  "deaths":[{"epochMillis":1000,"causeCategory":"fall","x":1.0},
                            {"epochMillis":"oops","causeCategory":"fall"},
                            42,
                            {"causeCategory":"fall"}]},
                 42,
                 {"uuid":"not-a-uuid","name":"BadUuid"},
                 {"uuid":"%s","name":"BadFlag","optOutPublic":"yes",
                  "deaths":[{"epochMillis":2000,"causeCategory":"fall"}]}
                ]}
                """.formatted(U1, U2);

        List<PlayerDeaths> parsed = CensusExport.parseBackupJson(json);
        assertNotNull(parsed, "corrupt elements must not fail the whole restore");
        assertEquals(2, parsed.size(), "only the two corrupt player entries are skipped");
        assertEquals("Good", parsed.get(0).name());
        assertTrue(parsed.get(0).optOutPublic());
        assertEquals(1, parsed.get(0).deaths().size(), "only the valid death survives");
        assertEquals(1000L, parsed.get(0).deaths().get(0).epochMillis());
        assertEquals("BadFlag", parsed.get(1).name());
        assertFalse(parsed.get(1).optOutPublic(), "wrong-typed flag defaults to false");
        assertEquals(1, parsed.get(1).deaths().size());
    }

    @Test
    void backupJsonReturnsNullOnlyForUnreadableRootOrPlayersArray() {
        assertNull(CensusExport.parseBackupJson("{not json"));
        assertNull(CensusExport.parseBackupJson("[]"));
        assertNull(CensusExport.parseBackupJson("{}"));
        assertNull(CensusExport.parseBackupJson("{\"players\":42}"));
        List<PlayerDeaths> empty = CensusExport.parseBackupJson("{\"players\":[]}");
        assertNotNull(empty);
        assertTrue(empty.isEmpty());
    }

    @Test
    void safetyBackupNamesAreUniqueWithinTheSameSecond() {
        long epoch = Instant.parse("2026-11-24T12:34:56.456Z").toEpochMilli();
        String first = BackupLogic.uniqueBackupFileName(epoch, ZoneOffset.UTC);
        String second = BackupLogic.uniqueBackupFileName(epoch, ZoneOffset.UTC);
        assertTrue(first.matches("deathcensus-backup-\\d{8}-\\d{6}-\\d{3}-[0-9a-f]{8}\\.json"), first);
        assertTrue(second.startsWith("deathcensus-backup-20261124-123456-456-"), second);
        assertFalse(first.equals(second), "same-second safety backups must never reuse a name");
    }

    @Test
    void writeBackupUsesUniqueNamesAndRefusesToReportAnExistingFileAsSuccess() throws IOException {
        String dir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(dir, "deathcensus.mainSourceDir system property is not set");
        String src = Files.readString(Path.of(dir, "com", "pengin0503", "deathcensus", "CensusExport.java"));

        assertTrue(src.contains("BackupLogic.uniqueBackupFileName(now, Config.displayTimeZone())"),
                "safety backups must use the unique filename rule");
        assertTrue(src.contains("protectedSource.getFileName().toString().equals(name)"),
                "the restore source must still be protected");

        int exists = src.indexOf("if (Files.exists(file))");
        assertTrue(exists > 0);
        int write = src.indexOf("Files.writeString(file", exists);
        assertTrue(write > exists);
        String collisionBranch = src.substring(exists, write);
        assertTrue(collisionBranch.contains("LOGGER.error"), "a collision must be logged");
        assertTrue(collisionBranch.contains("return null"), "a stale file must never be reported as a new backup");
        assertFalse(collisionBranch.contains("return file"));
    }
}
