package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PR-10: only known UUID filenames are read; missing files become empty stats. */
class VanillaStatsSourceTest {
    private static final UUID KNOWN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MISSING = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID UNRELATED = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void readsOnlyRequestedUuidFiles(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("stats"));
        write(dir, KNOWN, "{\"stats\":{\"minecraft:custom\":{\"minecraft:deaths\":5}}}");
        write(dir, UNRELATED, "{\"stats\":{\"minecraft:custom\":{\"minecraft:deaths\":99}}}");

        Map<UUID, VanillaStats> read = VanillaStatsSource.readFor(dir.resolve("stats"), List.of(KNOWN, MISSING));
        assertEquals(2, read.size());
        assertEquals(5, read.get(KNOWN).deaths());
        assertEquals(VanillaStats.EMPTY, read.get(MISSING));
        assertFalse(read.containsKey(UNRELATED), "unrelated stat files must be ignored");
    }

    @Test
    void malformedAndZeroDeathFilesReturnEmpty(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("stats"));
        write(dir, KNOWN, "not json");
        write(dir, MISSING, "{\"stats\":{\"minecraft:custom\":{\"minecraft:deaths\":0}}}");
        Map<UUID, VanillaStats> read = VanillaStatsSource.readFor(dir.resolve("stats"), List.of(KNOWN, MISSING));
        assertTrue(read.get(KNOWN).isEmpty());
        assertTrue(read.get(MISSING).isEmpty());
    }

    private static void write(Path dir, UUID uuid, String json) throws IOException {
        Files.writeString(dir.resolve("stats").resolve(uuid + ".json"), json);
    }
}
