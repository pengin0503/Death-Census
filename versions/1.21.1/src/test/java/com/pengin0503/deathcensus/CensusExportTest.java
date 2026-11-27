package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Export/backup regressions (review S3, S10, M4). */
class CensusExportTest {
    private static final String U1 = "00000000-0000-0000-0000-000000000001";
    private static final String U2 = "00000000-0000-0000-0000-000000000002";

    private static DeathEntry death(String uuid, String name, long epoch, String message,
                                    String killer, String excuse, String lastWords) {
        return new DeathEntry(uuid, name, epoch, 1L, 500, 400, "minecraft:overworld",
                1.0, 64.0, -2.0, "player", "minecraft:player", message,
                "minecraft:player", killer, "survival", 5, excuse, lastWords, 0);
    }

    @Test
    void corruptBackupElementsAreSkippedWithoutDroppingValidSiblings() {
        String json = """
                {"players":[
                  {"uuid":"%s","name":"Alice","optOutPublic":false,"deaths":[
                    {"epochMillis":1000,"playerName":"Alice","causeCategory":"fall","x":1.0},
                    42,
                    {"epochMillis":"oops","causeCategory":"fall"},
                    {"causeCategory":"fall"}
                  ]},
                  "not an object",
                  {"uuid":42,"name":"BadUuid"},
                  {"uuid":"%s","name":"Bob","optOutPublic":true,"deaths":[
                    {"epochMillis":2000,"playerName":"Bob","causeCategory":"lava"}
                  ]}
                ]}
                """.formatted(U1, U2);

        List<PlayerDeaths> parsed = CensusExport.parseBackupJson(json);
        assertNotNull(parsed);
        assertEquals(2, parsed.size(), "only corrupt player entries may be skipped");
        assertEquals("Alice", parsed.get(0).name());
        assertFalse(parsed.get(0).optOutPublic());
        assertEquals(1, parsed.get(0).deaths().size(), "one valid death must survive");
        assertEquals(1000L, parsed.get(0).deaths().get(0).epochMillis());
        assertEquals("Bob", parsed.get(1).name());
        assertTrue(parsed.get(1).optOutPublic());
        assertEquals(2000L, parsed.get(1).deaths().get(0).epochMillis());
    }

    @Test
    void malformedBackupRootStillReturnsNull() {
        assertNull(CensusExport.parseBackupJson("not json"));
        assertNull(CensusExport.parseBackupJson("{\"players\":{}}"));
        assertNull(CensusExport.parseBackupJson("[]"));
    }

    @Test
    void publicExportsScrubHiddenOpponentButBackupKeepsIt() {
        DeathEntry aliceDeath = death(U1, "Alice", 1000L,
                "Alice was slain by Bob", "Bob", "why Bob", "gg Bob");
        PlayerDeaths alice = new PlayerDeaths(U1, "Alice", false, List.of(aliceDeath));
        PlayerDeaths bob = new PlayerDeaths(U2, "Bob", true, List.of());
        List<PlayerDeaths> all = List.of(alice, bob);
        List<PlayerDeaths> exported = List.of(alice);

        String json = ExportRenderers.toJson(exported, 123L, all);
        assertFalse(json.contains("Bob"), json);
        assertTrue(json.contains("Anonymous"), json);
        assertFalse(CensusExport.parseBackupJson(json).get(0).deaths().get(0).killerName().contains("Bob"));

        String csv = ExportRenderers.toCsv(exported, all);
        assertFalse(csv.contains("Bob"), csv);
        String html = ExportRenderers.toHtml(exported, 123L, ZoneOffset.UTC, all);
        assertFalse(html.contains("Bob"), html);

        // Backups are operator-only: raw values must survive for restore.
        String backup = ExportRenderers.toBackupJson(all, 123L);
        assertTrue(backup.contains("Bob"), backup);
        assertTrue(backup.contains("Alice was slain by Bob"), backup);
    }

    @Test
    void safetyBackupUsesUniqueNameAndRefusesCollisions() throws IOException {
        String sourceDir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(sourceDir, "deathcensus.mainSourceDir system property is not set");
        String source = Files.readString(Path.of(sourceDir, "com", "pengin0503", "deathcensus", "CensusExport.java"));
        int start = source.indexOf("private static Path writeBackup(MinecraftServer server, @Nullable Path protectedSource)");
        assertTrue(start >= 0, "missing safety backup method");
        int end = source.indexOf("private static void pruneBackups(", start);
        String method = source.substring(start, end);
        assertTrue(method.contains("BackupLogic.uniqueBackupFileName(now, Config.displayTimeZone())"));
        assertFalse(method.contains("BackupLogic.backupFileName("), "second-granularity names must not be reused");
        assertTrue(method.contains("protectedSource.getFileName().toString().equals(name)"),
                "the protected restore-source guard must stay");
        assertTrue(method.contains("Files.exists(file)") && method.contains("return null;"),
                "an existing file must never be reported as a fresh safety backup");
        assertTrue(method.contains("LOGGER.error"), "the collision must be logged as an error");
        assertTrue(method.contains("ExportRenderers.toBackupJson"), "backups must stay unredacted");

        String a = BackupLogic.uniqueBackupFileName(1_700_000_000_000L, ZoneOffset.UTC);
        String b = BackupLogic.uniqueBackupFileName(1_700_000_000_001L, ZoneOffset.UTC);
        assertFalse(a.equals(b));
        assertFalse(a.equals(BackupLogic.backupFileName(1_700_000_000_000L, ZoneOffset.UTC)));
        assertTrue(a.matches("deathcensus-backup-\\d{8}-\\d{6}-\\d{3}-[0-9a-f]{8}\\.json"), a);
    }
}
