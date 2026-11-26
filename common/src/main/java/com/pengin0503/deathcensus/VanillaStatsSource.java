package com.pengin0503.deathcensus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reads {@code <world>/stats/<uuid>.json} only for UUIDs supplied by the
 * caller (DESIGN 1.3.0 §9: known players only, never player names). Every
 * requested UUID gets an entry: {@link VanillaStats#EMPTY} means missing,
 * unreadable or malformed file, and is skipped quietly by the import.
 */
public final class VanillaStatsSource {
    private VanillaStatsSource() {
    }

    public static Map<UUID, VanillaStats> readFor(Path statsDir, Collection<UUID> knownUuids) {
        Map<UUID, VanillaStats> out = new LinkedHashMap<>();
        if (statsDir == null || knownUuids == null) {
            return out;
        }
        for (UUID uuid : knownUuids) {
            if (uuid == null) {
                continue;
            }
            out.put(uuid, readOne(statsDir, uuid));
        }
        return out;
    }

    private static VanillaStats readOne(Path statsDir, UUID uuid) {
        Path file = statsDir.resolve(uuid + ".json");
        if (!Files.isRegularFile(file)) {
            return VanillaStats.EMPTY;
        }
        try {
            return VanillaStatsParser.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return VanillaStats.EMPTY;
        }
    }
}
