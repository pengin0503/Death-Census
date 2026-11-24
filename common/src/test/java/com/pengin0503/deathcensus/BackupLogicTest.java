package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupLogicTest {

    @Test
    void toDeleteReturnsOldestExcessAndClampsKeep() {
        List<String> files = List.of("b1.json", "b2.json", "b3.json", "b4.json", "b5.json");
        assertEquals(List.of("b1.json", "b2.json"), BackupLogic.toDelete(files, 3));
        assertTrue(BackupLogic.toDelete(files, 5).isEmpty());
        assertTrue(BackupLogic.toDelete(files, 10).isEmpty());
        assertTrue(BackupLogic.toDelete(List.of(), 3).isEmpty());
        assertTrue(BackupLogic.toDelete(null, 3).isEmpty());
        // keep < 1 clamped to 1
        assertEquals(List.of("b1.json", "b2.json", "b3.json", "b4.json"), BackupLogic.toDelete(files, 0));
    }

    @Test
    void backupFileNameFormatsTimestampInZone() {
        long epoch = Instant.parse("2026-11-24T12:34:56Z").toEpochMilli();
        assertEquals(
                "deathcensus-backup-20261124-123456.json",
                BackupLogic.backupFileName(epoch, ZoneOffset.UTC)
        );
        assertEquals(
                "deathcensus-backup-20261124-213456.json",
                BackupLogic.backupFileName(epoch, ZoneId.of("Asia/Tokyo"))
        );
        assertTrue(
                BackupLogic.backupFileName(epoch, null)
                        .matches("deathcensus-backup-\\d{8}-\\d{6}\\.json")
        );
    }
}
