package com.pengin0503.deathcensus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** Pure filename rule; anonymized files never carry a player name. */
    static String exportFileName(String format, @Nullable String playerName, boolean anonymize, ZoneId zone) {
        String suffix = "";
        if (!anonymize && playerName != null && !playerName.isEmpty()) {
            suffix = "-" + sanitizeFilePart(playerName);
        }
        return "deathcensus-" + timestamp(zone) + (anonymize ? "-anonymized" : "") + suffix + "." + format;
    }

    /** Writes an export file and returns its path. Format is html, json or csv. */
    @Nullable
    public static Path writeExport(MinecraftServer server, String format, @Nullable UUID onlyPlayer) {
        return writeExport(server, format, onlyPlayer, false);
    }

    /**
     * As {@link #writeExport(MinecraftServer, String, UUID)}, but renders a
     * redacted copy when {@code anonymize} is set (DESIGN 1.3.0 §6). Opt-out
     * filtering still happens before anonymization; the normal path is unchanged.
     */
    @Nullable
    public static Path writeExport(MinecraftServer server, String format, @Nullable UUID onlyPlayer,
                                   boolean anonymize) {
        CensusData data = CensusData.get(server);
        // Keep the full snapshot for the privacy dictionaries: filtering below
        // only decides which records are exported (reviews S3/S4).
        List<PlayerDeaths> allPlayers = data.snapshot();
        List<PlayerDeaths> players = new ArrayList<>();
        for (PlayerDeaths pd : allPlayers) {
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
        boolean includeContext = Config.deathContextInExports();
        String contextHeader = ServerLang.formatForLang(ServerLang.DEFAULT_LANG,
                "command.deathcensus.export.context");
        String awardsHeader = ServerLang.formatForLang(ServerLang.DEFAULT_LANG,
                "command.deathcensus.export.awards");
        List<String> awardLines = CensusCommands.awardLines(
                CensusCommands.computeAwards(server, players, false), ServerLang.DEFAULT_LANG);
        String content = switch (format) {
            case "json" -> ExportRenderers.toJson(players, now, anonymize, includeContext, allPlayers,
                    awardLines, awardsHeader);
            case "csv" -> ExportRenderers.toCsv(players, anonymize, includeContext, allPlayers,
                    awardLines, awardsHeader);
            default -> ExportRenderers.toHtml(players, now, zone, anonymize, includeContext,
                    contextHeader, allPlayers, awardLines, awardsHeader);
        };
        String playerName = onlyPlayer != null && !players.isEmpty() ? players.get(0).name() : null;
        Path file = exportsDir(server).resolve(exportFileName(format, playerName, anonymize, zone));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            DeathCensus.LOGGER.warn("[DeathCensus] Could not write export {}", file, e);
            return null;
        }
    }

    /**
     * Writes a standalone HTML report for an arbitrary inclusive season period
     * (DESIGN 1.3.0 §5). DESIGN §8: opted-out players are excluded from this
     * named, public-facing report regardless of {@code includeOptedOutInExports};
     * their deaths still count anonymously in the Discord digest's totals.
     */
    @Nullable
    public static Path writeSeasonHtml(MinecraftServer server, SeasonRange range) {
        if (server == null || range == null) {
            return null;
        }
        // The full snapshot is the privacy dictionary source; the date range and
        // opt-out gate below only filter which records are rendered (S3).
        List<PlayerDeaths> allPlayers = CensusData.get(server).snapshot();
        List<PlayerDeaths> players = new ArrayList<>();
        for (PlayerDeaths player : CensusStats.filterRange(
                allPlayers, range.fromMillisInclusive(), range.toMillisInclusive())) {
            if (!player.optOutPublic()) {
                players.add(player);
            }
        }
        ZoneId zone = Config.displayTimeZone();
        List<String> awardLines = CensusCommands.awardLines(
                CensusCommands.computeAwards(server, players, false), ServerLang.DEFAULT_LANG);
        String content = ExportRenderers.toHtml(players, System.currentTimeMillis(), zone, false,
                Config.deathContextInExports(), ServerLang.formatForLang(ServerLang.DEFAULT_LANG,
                        "command.deathcensus.export.context"), allPlayers, awardLines,
                ServerLang.formatForLang(ServerLang.DEFAULT_LANG, "command.deathcensus.export.awards"));
        Path file = exportsDir(server).resolve("season-" + range.fileNameStamp(zone) + ".html");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            DeathCensus.LOGGER.warn("[DeathCensus] Could not write season report {}", file, e);
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
        // Millisecond + UUID uniqueness (M4): a freshly requested safety backup
        // must never reuse a file written by an earlier backup in the same second.
        String name = BackupLogic.uniqueBackupFileName(now, Config.displayTimeZone());
        if (protectedSource != null && protectedSource.getFileName().toString().equals(name)) {
            name = name.substring(0, name.length() - ".json".length()) + "-pre-restore.json";
        }
        Path file = backupsDir(server).resolve(name);
        try {
            Files.createDirectories(file.getParent());
            // Even a random collision must be refused: reporting an existing
            // file as a new safety backup would hide a stale pre-mutation copy.
            if (Files.exists(file)) {
                DeathCensus.LOGGER.error("[DeathCensus] Backup name collision, refusing to overwrite '{}'", file);
                return null;
            }
            Files.writeString(file, ExportRenderers.toBackupJson(
                    data.snapshot(), now, data.chronicleEvents()), StandardCharsets.UTF_8);
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
            List<ChronicleEvent> chronicle = new ArrayList<>();
            List<PlayerDeaths> players = parseBackupJson(json, chronicle);
            if (players == null) {
                return -1;
            }
            Path safety = writeBackup(server, file);
            if (safety == null) {
                DeathCensus.LOGGER.error("[DeathCensus] Restore aborted: could not write a safety backup for '{}'", file);
                return -1;
            }
            CensusData.get(server).replaceAll(players, chronicle);
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

    /**
     * Parses the JSON produced by {@link ExportRenderers#toJson}; null only when
     * the file itself is unreadable. Per DESIGN §3.3, a corrupt player or death
     * entry is skipped with a warning while all valid siblings are retained.
     */
    @Nullable
    static List<PlayerDeaths> parseBackupJson(String json) {
        return parseBackupJson(json, null);
    }

    /**
     * As above, filling {@code chronicleOut} with the optional 1.4.0 aggregate.
     * Old backups omit every new key; all of them default to empty/absent.
     */
    @Nullable
    static List<PlayerDeaths> parseBackupJson(String json, @Nullable List<ChronicleEvent> chronicleOut) {
        JsonObject root;
        JsonArray playersJson;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
            playersJson = root.getAsJsonArray("players");
        } catch (RuntimeException e) {
            return null;
        }
        if (playersJson == null) {
            return null;
        }
        List<PlayerDeaths> players = new ArrayList<>();
        int skippedPlayers = 0;
        int skippedDeaths = 0;
        int skippedEpochless = 0;
        int skippedChronicle = 0;
        JsonArray chronicleJson = root.getAsJsonArray("chronicleEvents");
        if (chronicleJson != null && chronicleOut != null) {
            for (JsonElement ce : chronicleJson) {
                try {
                    if (!ce.isJsonObject()) {
                        skippedChronicle++;
                        continue;
                    }
                    ChronicleEvent event = chronicleEvent(ce.getAsJsonObject());
                    if (event.epochMillis() <= 0L) {
                        skippedChronicle++;
                        continue;
                    }
                    chronicleOut.add(event);
                } catch (RuntimeException e) {
                    skippedChronicle++;
                }
            }
        }
        for (JsonElement pe : playersJson) {
            try {
                if (!pe.isJsonObject()) {
                    skippedPlayers++;
                    continue;
                }
                JsonObject p = pe.getAsJsonObject();
                JsonElement uuidElement = p.get("uuid");
                if (uuidElement == null || !uuidElement.isJsonPrimitive()) {
                    skippedPlayers++;
                    continue;
                }
                String uuid = uuidElement.getAsString();
                UUID.fromString(uuid);
                String name = str(p, "name");
                boolean optOut = bool(p, "optOutPublic");
                boolean hideCoordinates = bool(p, "hideCoordinates");
                boolean hidePvpOpponent = bool(p, "hidePvpOpponent");
                boolean discordOptOut = bool(p, "discordOptOut");
                // §9: baseline metadata is backup-only; absent in old backups and public exports.
                int vanillaBaseline = Math.max(0, integer(p, "vanillaDeathsBaseline"));
                Map<String, Integer> baselineCauses = baselineCauses(p.get("vanillaBaselineCauses"));
                List<DeathEntry> deaths = new ArrayList<>();
                JsonArray deathsJson = p.getAsJsonArray("deaths");
                if (deathsJson != null) {
                    for (JsonElement de : deathsJson) {
                        try {
                            if (!de.isJsonObject()) {
                                skippedDeaths++;
                                continue;
                            }
                            JsonObject d = de.getAsJsonObject();
                            // Absent epoch means "unknown time", not epoch 0: never restore it as a record.
                            if (!d.has("epochMillis")) {
                                skippedEpochless++;
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
                                    integer(d, "fCount"),
                                    witnesses(d.get("witnesses")),
                                    context(d.get("context"))));
                        } catch (RuntimeException e) {
                            skippedDeaths++;
                        }
                    }
                }
                players.add(new PlayerDeaths(uuid, name, optOut,
                        hideCoordinates, hidePvpOpponent, discordOptOut, deaths,
                        vanillaBaseline, baselineCauses, nearDeaths(p.get("nearDeaths"))));
            } catch (RuntimeException e) {
                skippedPlayers++;
            }
        }
        if (skippedEpochless > 0) {
            DeathCensus.LOGGER.warn("[DeathCensus] Skipped {} backup death record(s) without epochMillis", skippedEpochless);
        }
        if (skippedDeaths > 0) {
            DeathCensus.LOGGER.warn("[DeathCensus] Skipped {} corrupt backup death record(s)", skippedDeaths);
        }
        if (skippedPlayers > 0) {
            DeathCensus.LOGGER.warn("[DeathCensus] Skipped {} corrupt backup player record(s)", skippedPlayers);
        }
        if (skippedChronicle > 0) {
            DeathCensus.LOGGER.warn("[DeathCensus] Skipped {} corrupt backup chronicle event(s)", skippedChronicle);
        }
        return players;
    }

    /** §9: optional category → count object; missing or wrong-typed values default to empty. */
    private static Map<String, Integer> baselineCauses(JsonElement value) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (value != null && value.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                JsonElement count = entry.getValue();
                if (count != null && count.isJsonPrimitive() && count.getAsJsonPrimitive().isNumber()) {
                    int parsed = Math.max(0, count.getAsInt());
                    if (parsed > 0) {
                        out.put(entry.getKey(), parsed);
                    }
                }
            }
        }
        return out;
    }

    /** Absent or wrong-typed flags default to false; old backups omit all three. */
    private static boolean bool(JsonObject o, String key) {
        JsonElement value = o.get(key);
        if (value == null) {
            return false;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean();
        }
        DeathCensus.LOGGER.warn("[DeathCensus] Ignoring non-boolean backup field '{}'", key);
        return false;
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
        JsonElement value = o.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return 0.0;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isString()) {
            String text = primitive.getAsString();
            // Public backups write the redaction marker for hidden coordinates;
            // restore it as the NaN sentinel instead of dropping the whole death.
            if (DeathRedactor.COORDINATE_MARKER.equals(text)) {
                return Double.NaN;
            }
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return primitive.getAsDouble();
    }

    // ---- §13.2.4: additive backup fields, absent → empty/default ----------

    /** Absent, wrong-typed or corrupt witness elements are skipped; the death survives. */
    private static List<WitnessRef> witnesses(JsonElement value) {
        if (value == null || !value.isJsonArray()) {
            return List.of();
        }
        List<WitnessRef> out = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (element == null || !element.isJsonObject()) {
                DeathCensus.LOGGER.warn("[DeathCensus] Skipping corrupt backup witness entry");
                continue;
            }
            JsonObject w = element.getAsJsonObject();
            WitnessRef ref = new WitnessRef(str(w, "uuid"), str(w, "nameAtDeath"));
            if (ref.isEmpty()) {
                DeathCensus.LOGGER.warn("[DeathCensus] Skipping corrupt backup witness entry");
                continue;
            }
            out.add(ref);
        }
        return out;
    }

    /** A missing or malformed context compound decodes as {@link DeathContext#EMPTY}. */
    private static DeathContext context(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            return DeathContext.EMPTY;
        }
        JsonObject c = value.getAsJsonObject();
        return new DeathContext(
                str(c, "mainHand"),
                stringList(c.get("armor")),
                stringList(c.get("effects")),
                str(c, "biome"),
                str(c, "weather"));
    }

    private static List<String> stringList(JsonElement value) {
        if (value == null || !value.isJsonArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (element != null && element.isJsonPrimitive()) {
                out.add(element.getAsString());
            } else {
                DeathCensus.LOGGER.warn("[DeathCensus] Skipping corrupt backup context string");
            }
        }
        return out;
    }

    /** Absent or malformed near-death history is empty; one bad event never drops the player. */
    private static List<NearDeathEvent> nearDeaths(JsonElement value) {
        if (value == null || !value.isJsonArray()) {
            return List.of();
        }
        List<NearDeathEvent> out = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (element == null || !element.isJsonObject()) {
                DeathCensus.LOGGER.warn("[DeathCensus] Skipping corrupt backup near-death event");
                continue;
            }
            JsonObject n = element.getAsJsonObject();
            NearDeathEvent event = new NearDeathEvent(
                    str(n, "uuid"), str(n, "name"), lng(n, "epochMillis"), str(n, "dimension"),
                    dbl(n, "x"), dbl(n, "y"), dbl(n, "z"),
                    str(n, "causeCategory"), str(n, "causeId"), nearDeathKind(str(n, "kind")));
            if (event.epochMillis() <= 0L || event.uuid().isEmpty()) {
                DeathCensus.LOGGER.warn("[DeathCensus] Skipping corrupt backup near-death event");
                continue;
            }
            out.add(event);
        }
        return out;
    }

    private static NearDeathEvent.Kind nearDeathKind(String raw) {
        try {
            return NearDeathEvent.Kind.valueOf(raw);
        } catch (RuntimeException e) {
            return NearDeathEvent.Kind.CANCELLED;
        }
    }

    private static ChronicleEvent chronicleEvent(JsonObject c) {
        return new ChronicleEvent(
                lng(c, "epochMillis"), lng(c, "gameDay"), chronicleKind(str(c, "kind")),
                str(c, "uuid"), str(c, "name"), str(c, "dimension"), str(c, "causeCategory"),
                integer(c, "count"), bool(c, "reconstructed"));
    }

    private static ChronicleEvent.Kind chronicleKind(String raw) {
        try {
            return ChronicleEvent.Kind.valueOf(raw);
        } catch (RuntimeException e) {
            return ChronicleEvent.Kind.SERVER_FIRST;
        }
    }
}
