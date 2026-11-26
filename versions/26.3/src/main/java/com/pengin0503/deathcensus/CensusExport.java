package com.pengin0503.deathcensus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/** File glue for exports, backups and restore (rendering/parsing rules live in common). */
public final class CensusExport {
    private CensusExport() {
    }

    public static Path exportsDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("deathcensus").resolve("exports");
    }

    public static Path backupsDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("deathcensus").resolve("backups");
    }

    static String sanitizeFilePart(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isEmpty() ? "player" : cleaned;
    }

    private static String timestamp(ZoneId zone) {
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
        return fmt.format(java.time.ZonedDateTime.now(zone));
    }

    /** Writes an export file and returns its path. Format is html, json or csv. */
    @Nullable
    public static Path writeExport(MinecraftServer server, String format, @Nullable UUID onlyPlayer) {
        CensusData data = CensusData.get(server);
        List<PlayerDeaths> players = new ArrayList<>();
        for (PlayerDeaths pd : data.snapshot()) {
            if (onlyPlayer != null) {
                if (pd.uuid().equals(onlyPlayer.toString())) {
                    // DESIGN §8: opted-out players appear in exports only with the config flag.
                    if (pd.optOutPublic() && !Config.includeOptedOutInExports()) {
                        DeathCensus.LOGGER.warn("[DeathCensus] Export of '{}' refused: the player opted out of public data "
                                + "and includeOptedOutInExports is false", pd.name());
                        return null;
                    }
                    players.add(pd);
                }
            } else if (!pd.optOutPublic() || Config.includeOptedOutInExports()) {
                players.add(pd);
            }
        }
        long now = System.currentTimeMillis();
        ZoneId zone = Config.displayTimeZone();
        String content = switch (format) {
            case "json" -> ExportRenderers.toJson(players, now);
            case "csv" -> ExportRenderers.toCsv(players);
            default -> ExportRenderers.toHtml(players, now, zone);
        };
        String suffix = "";
        if (onlyPlayer != null && !players.isEmpty()) {
            suffix = "-" + sanitizeFilePart(players.get(0).name());
        }
        Path file = exportsDir(server).resolve("deathcensus-" + timestamp(zone) + suffix + "." + format);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            DeathCensus.LOGGER.warn("[DeathCensus] Could not write export {}", file, e);
            return null;
        }
    }

    /** Writes a JSON backup of all data (including opted-out players) and prunes old generations. */
    @Nullable
    public static Path writeBackup(MinecraftServer server) {
        return writeBackup(server, null);
    }

    /**
     * As {@link #writeBackup(MinecraftServer)}, but never deletes or overwrites
     * {@code protectedSource} (used by restore for its safety backup).
     */
    @Nullable
    private static Path writeBackup(MinecraftServer server, @Nullable Path protectedSource) {
        CensusData data = CensusData.get(server);
        long now = System.currentTimeMillis();
        String name = BackupLogic.backupFileName(now, Config.displayTimeZone());
        // Backup names have second granularity: a safety backup made in the same
        // second as the restore source would otherwise target the source itself.
        if (protectedSource != null && protectedSource.getFileName().toString().equals(name)) {
            name = name.substring(0, name.length() - ".json".length()) + "-pre-restore.json";
        }
        Path file = backupsDir(server).resolve(name);
        try {
            Files.createDirectories(file.getParent());
            // Two auto-backups can fire in the same second (server stop + final world save).
            if (Files.exists(file)) {
                return file;
            }
            Files.writeString(file, ExportRenderers.toJson(data.snapshot(), now), StandardCharsets.UTF_8);
            // The just-written safety backup must not be pruned while protecting the source.
            pruneBackups(server, protectedSource, file);
            return file;
        } catch (IOException e) {
            DeathCensus.LOGGER.warn("[DeathCensus] Could not write backup {}", file, e);
            return null;
        }
    }

    private static void pruneBackups(MinecraftServer server, @Nullable Path... protectedPaths) {
        Path dir = backupsDir(server);
        if (!Files.isDirectory(dir)) {
            return;
        }
        java.util.Set<String> protectedNames = new java.util.HashSet<>();
        if (protectedPaths != null) {
            for (Path path : protectedPaths) {
                if (path != null) {
                    protectedNames.add(path.getFileName().toString());
                }
            }
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<String> names = stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("deathcensus-backup-") && n.endsWith(".json"))
                    .filter(n -> !protectedNames.contains(n))
                    .sorted()
                    .toList();
            for (String old : BackupLogic.toDelete(names, Config.autoBackupGenerations())) {
                Files.deleteIfExists(dir.resolve(old));
            }
        } catch (IOException e) {
            DeathCensus.LOGGER.warn("[DeathCensus] Could not prune backups in {}", dir, e);
        }
    }

    public static List<String> listBackups(MinecraftServer server) {
        Path dir = backupsDir(server);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("deathcensus-backup-") && n.endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    static void autoBackupOnStop(MinecraftServer server) {
        if (server == null || !Config.autoBackupEnabled()) {
            return;
        }
        CensusData data = CensusData.get(server);
        if (!data.isEmpty()) {
            writeBackup(server);
        }
    }

    private static final long AUTO_BACKUP_INTERVAL_MILLIS = 60L * 60L * 1000L;
    private static long lastAutoBackupMillis = 0L;

    /** Auto-backup on world save, throttled to at most once per hour. */
    static void autoBackupOnWorldSave(MinecraftServer server) {
        if (server == null || !Config.autoBackupEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (lastAutoBackupMillis != 0L && now - lastAutoBackupMillis < AUTO_BACKUP_INTERVAL_MILLIS) {
            return;
        }
        CensusData data = CensusData.get(server);
        if (data.isEmpty()) {
            return;
        }
        if (writeBackup(server) != null) {
            lastAutoBackupMillis = now;
        }
    }

    /** Forgets the auto-backup throttle so the next world save can back up again. */
    static void resetAutoBackupThrottler() {
        lastAutoBackupMillis = 0L;
    }

    /**
     * Restores data from a backup file in the backups directory. A safety backup
     * of the current data is written first.
     *
     * @return number of death records restored, or -1 on failure
     */
    public static int restore(MinecraftServer server, String fileName) {
        if (fileName == null || fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            return -1;
        }
        Path file = backupsDir(server).resolve(fileName);
        if (!Files.isRegularFile(file)) {
            return -1;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            List<PlayerDeaths> players = parseBackupJson(json);
            if (players == null) {
                return -1;
            }
            Path safety = writeBackup(server, file);
            if (safety == null) {
                DeathCensus.LOGGER.error("[DeathCensus] Restore aborted: could not write a safety backup for '{}'", file);
                return -1;
            }
            CensusData.get(server).replaceAll(players);
            int total = 0;
            for (PlayerDeaths pd : players) {
                total += pd.deaths().size();
            }
            return total;
        } catch (IOException e) {
            DeathCensus.LOGGER.warn("[DeathCensus] Could not restore backup {}", file, e);
            return -1;
        }
    }

    /** Parses the JSON produced by {@link ExportRenderers#toJson}; null on malformed input. */
    @Nullable
    static List<PlayerDeaths> parseBackupJson(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray playersJson = root.getAsJsonArray("players");
            if (playersJson == null) {
                return null;
            }
            List<PlayerDeaths> players = new ArrayList<>();
            int skippedTotal = 0;
            for (JsonElement pe : playersJson) {
                JsonObject p = pe.getAsJsonObject();
                String uuid = p.get("uuid").getAsString();
                UUID.fromString(uuid);
                String name = p.has("name") ? p.get("name").getAsString() : "";
                boolean optOut = p.has("optOutPublic") && p.get("optOutPublic").getAsBoolean();
                List<DeathEntry> deaths = new ArrayList<>();
                int skipped = 0;
                JsonArray deathsJson = p.getAsJsonArray("deaths");
                if (deathsJson != null) {
                    for (JsonElement de : deathsJson) {
                        JsonObject d = de.getAsJsonObject();
                        // Absent epoch means "unknown time", not epoch 0: never restore it as a record.
                        if (!d.has("epochMillis")) {
                            skipped++;
                            continue;
                        }
                        deaths.add(new DeathEntry(
                                uuid,
                                str(d, "playerName"),
                                lng(d, "epochMillis"),
                                lng(d, "gameDay"),
                                integer(d, "playTicks"),
                                integer(d, "survivalTicks"),
                                str(d, "dimension"),
                                dbl(d, "x"), dbl(d, "y"), dbl(d, "z"),
                                str(d, "causeCategory"),
                                str(d, "causeId"),
                                str(d, "deathMessage"),
                                str(d, "killerType"),
                                str(d, "killerName"),
                                str(d, "gameMode"),
                                integer(d, "experienceLevel"),
                                str(d, "excuse"),
                                str(d, "lastWords"),
                                integer(d, "fCount")));
                    }
                }
                players.add(new PlayerDeaths(uuid, name, optOut, deaths));
                skippedTotal += skipped;
            }
            if (skippedTotal > 0) {
                DeathCensus.LOGGER.warn("[DeathCensus] Skipped {} backup death record(s) without epochMillis", skippedTotal);
            }
            return players;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static long lng(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : 0L;
    }

    private static int integer(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : 0;
    }

    private static double dbl(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : 0.0;
    }
}
