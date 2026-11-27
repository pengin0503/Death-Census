package com.pengin0503.deathcensus;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Record player deaths in this world.")
            .define("enabled", true);

    public static final ModConfigSpec.BooleanValue RECORD_CREATIVE_DEATHS = BUILDER
            .comment("Also record deaths while a player is in creative or spectator mode.")
            .define("recordCreativeDeaths", true);

    public static final ModConfigSpec.BooleanValue MILESTONE_ANNOUNCEMENTS = BUILDER
            .comment("Announce milestone deaths in chat and Discord: the server's first death, every 100 deaths,",
                    "a player's first death, and a player completing all 17 cause categories.")
            .define("milestoneAnnouncements", true);

    public static final ModConfigSpec.BooleanValue CAPTURE_WITNESSES = BUILDER
            .comment("Capture nearby online players as witnesses at death time (DESIGN 1.4.0 §1).",
                    "Witnesses are captured only while the event fires; enabling later does not backfill old records.",
                    "Witness names are filtered by each witness's own optOutPublic at render time.")
            .define("captureWitnesses", true);

    public static final ModConfigSpec.IntValue WITNESS_RADIUS_BLOCKS = BUILDER
            .comment("Radius in blocks around the death position used by the witness scan (8-64).")
            .defineInRange("witnessRadiusBlocks", 24, 8, 64);

    public static final ModConfigSpec.IntValue WITNESS_MAX_PER_DEATH = BUILDER
            .comment("Maximum witnesses stored per death, nearest first (0-16). Zero disables storage entirely.")
            .defineInRange("witnessMaxPerDeath", 8, 0, 16);

    public static final ModConfigSpec.BooleanValue WITNESSES_IN_DISCORD = BUILDER
            .comment("Include witnesses in realtime Discord death posts (default false; the death line stays compact).",
                    "A future digest section is the intended opt-in seam; this PR adds no Discord witness text.")
            .define("witnessesInDiscord", false);

    public static final ModConfigSpec.BooleanValue CAPTURE_DEATH_CONTEXT = BUILDER
            .comment("Capture registry-id death context at death time (DESIGN 1.4.0 §11): main hand, armor,",
                    "active effects, biome and weather. Enabling later cannot backfill context for old deaths.",
                    "Only ids are stored; item display names and NBT are never captured.")
            .define("captureDeathContext", true);

    public static final ModConfigSpec.IntValue DEATH_CONTEXT_MAX_EFFECTS = BUILDER
            .comment("Maximum active effects stored per death (0-16). Zero stores no effects; the rest of the",
                    "death context is unaffected. Effects are encoded as id:amplifier.")
            .defineInRange("deathContextMaxEffects", 8, 0, 16);

    public static final ModConfigSpec.BooleanValue DEATH_CONTEXT_IN_DISCORD = BUILDER
            .comment("Include death context in realtime Discord death posts (default false).",
                    "Realtime Discord content is unchanged by this PR; this is the opt-in seam for a later decision.")
            .define("deathContextInDiscord", false);

    public static final ModConfigSpec.BooleanValue DEATH_CONTEXT_IN_EXPORTS = BUILDER
            .comment("Include death context fields in JSON/CSV/HTML exports (default true).",
                    "Backups always keep it; turning this off only affects public export surfaces.")
            .define("deathContextInExports", true);

    // 1.4.0 PR-5 (§3): world chronicle; old worlds get a labelled, records-only reconstruction.
    public static final ModConfigSpec.BooleanValue CHRONICLE_ENABLED = BUILDER
            .comment("Persist world chronicle events (DESIGN 1.4.0 §3).",
                    "Missing history is never filled in; old worlds reconstruct only provable rows.")
            .define("chronicleEnabled", true);

    public static final ModConfigSpec.IntValue CHRONICLE_MAX_EVENTS = BUILDER
            .comment("Maximum persisted chronicle rows (0-1000); the oldest are dropped beyond this.")
            .defineInRange("chronicleMaxEvents", 200, 0, 1000);

    public static final ModConfigSpec.BooleanValue CHRONICLE_IN_EXPORT = BUILDER
            .comment("Include the chronicle section in JSON/CSV/HTML exports (default true).")
            .define("chronicleInExport", true);

    public static final ModConfigSpec.BooleanValue CHRONICLE_IN_DISCORD = BUILDER
            .comment("Opt-in seam for future chronicle Discord rows (default false; PR-5 adds no new posts).")
            .define("chronicleInDiscord", false);

    // 1.4.0 PR-4 (§2): derived awards are read-only; no stored winner history.
    public static final ModConfigSpec.BooleanValue AWARDS_ENABLED = BUILDER
            .comment("Enable derived awards/titles: /dc awards, the awards ranking page, export and digest sections.",
                    "Awards are recomputed from retained records; disabling them hides every award surface.")
            .define("awardsEnabled", true);

    public static final ModConfigSpec.ConfigValue<String> AWARDS_DEFAULT_PERIOD = BUILDER
            .comment("Default period for /dc awards and the awards ranking page (today|7d|30d|all).")
            .define("awardsDefaultPeriod", "all", Config::validateAwardsPeriod);

    public static final ModConfigSpec.BooleanValue AWARDS_IN_DIGEST = BUILDER
            .comment("Include the derived awards section in periodic digests and /dc digest season.",
                    "Realtime Discord death lines stay compact and never carry awards.")
            .define("awardsInDigest", true);

    public static final ModConfigSpec.IntValue AWARDS_MIN_COUNT = BUILDER
            .comment("A named award winner must beat this count (1-100); lower counts stay anonymous.")
            .defineInRange("awardsMinCount", 1, 1, 100);

    // 1.4.0 PR-6 (§4): nemesis tables are derived from killer fields; nothing new is stored.
    public static final ModConfigSpec.BooleanValue NEMESIS_ENABLED = BUILDER
            .comment("Enable derived nemesis tables: /dc nemesis, the Nemesis ranking page and export section.",
                    "All tables are recomputed from retained records; disabling hides every nemesis surface.")
            .define("nemesisEnabled", true);

    public static final ModConfigSpec.IntValue NEMESIS_MIN_MOB_COUNT = BUILDER
            .comment("Minimum kill count for a mob-species nemesis row (1-100).")
            .defineInRange("nemesisMinMobCount", 3, 1, 100);

    public static final ModConfigSpec.IntValue NEMESIS_MIN_NAMED_COUNT = BUILDER
            .comment("Minimum kill count for a named-mob or PvP rival nemesis row (1-100).")
            .defineInRange("nemesisMinNamedCount", 2, 1, 100);

    public static final ModConfigSpec.BooleanValue NEMESIS_IN_DIGEST = BUILDER
            .comment("Opt-in seam for a future nemesis Discord section (default false; PR-6 adds no posts).")
            .define("nemesisInDigest", false);

    public static final ModConfigSpec.IntValue MAX_RECORDS_PER_PLAYER = BUILDER
            .comment("Maximum number of death records kept per player; the oldest are dropped beyond this.")
            .defineInRange("maxRecordsPerPlayer", 10000, 100, 100000);

    public static final ModConfigSpec.ConfigValue<String> DISPLAY_TIME_ZONE = BUILDER
            .comment("Timezone ID used for dates formatted by the server (empty = server default). Examples: \"UTC\", \"Asia/Tokyo\".")
            .define("displayTimeZone", "", Config::validateTimeZone);

    public static final ModConfigSpec.ConfigValue<String> DISCORD_WEBHOOK_URL = BUILDER
            .comment("Optional Discord webhook URL for death notifications. Leave empty to disable.",
                    "You can also use \"env:VAR_NAME\" to read the URL from an environment variable.",
                    "Treat this URL as a secret; it is never printed to logs or chat.")
            .define("discordWebhookUrl", "");

    public static final ModConfigSpec.ConfigValue<String> DISCORD_DIGEST_WEBHOOK_URL = BUILDER
            .comment("Optional Discord webhook URL for the periodic digest. Leave empty to use discordWebhookUrl.",
                    "A non-empty but invalid URL disables the digest channel (no silent fallback); see /dc doctor.",
                    "You can also use \"env:VAR_NAME\" to read the URL from an environment variable.",
                    "Treat this URL as a secret; it is never printed to logs or chat.")
            .define("discordDigestWebhookUrl", "");

    public static final ModConfigSpec.BooleanValue DISCORD_NOTIFY_DEATHS = BUILDER
            .comment("Post a Discord message for every recorded death (players who opted out are skipped).")
            .define("discordNotifyDeaths", true);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> DISCORD_NOTIFY_DIMENSIONS = BUILDER
            .comment("Realtime death Discord notifications only: allow-list of exact dimension ids,",
                    "e.g. [\"minecraft:overworld\", \"minecraft:the_nether\"]. Empty = all dimensions.",
                    "Ids are case-sensitive (same convention as /dc history <player> <filter>);",
                    "blank entries or whitespace are rejected. Digests, milestones and chat announcements are not filtered.")
            .defineListAllowEmpty("discordNotifyDimensions", List.of(),
                    () -> "minecraft:overworld", Config::validateDimensionId);

    public static final ModConfigSpec.ConfigValue<String> DISCORD_NOTIFY_PVP_MODE = BUILDER
            .comment("Realtime death Discord notifications only: all, pvp_only or off (default: all).",
                    "pvp_only keeps player-caused deaths (cause category 'player', which includes",
                    "player-attributed explosions and projectiles); off suppresses them. Digests,",
                    "milestones and chat announcements are not filtered.")
            .define("discordNotifyPvpMode", "all", Config::validatePvpMode);

    public static final ModConfigSpec.ConfigValue<String> DISCORD_LANGUAGE = BUILDER
            .comment("Language for Discord webhook notifications (\"en_us\" or \"ja_jp\").")
            .define("discordLanguage", "en_us", Config::validateLanguage);

    public static final ModConfigSpec.BooleanValue DISCORD_DIGEST_ENABLED = BUILDER
            .comment("Send a periodic Discord digest summarizing deaths since the previous digest.")
            .define("discordDigestEnabled", false);

    public static final ModConfigSpec.IntValue DISCORD_DIGEST_INTERVAL_DAYS = BUILDER
            .comment("Interval in days between Discord digest messages (1-30).")
            .defineInRange("discordDigestIntervalDays", 7, 1, 30);

    public static final ModConfigSpec.IntValue DISCORD_RETRY_MAX_ATTEMPTS = BUILDER
            .comment("Total attempts per Discord webhook message, including the initial send (1-6).",
                    "Retries happen on the daemon Discord worker; failed messages are dropped after the last attempt.")
            .defineInRange("discordRetryMaxAttempts", 4, 1, 6);

    public static final ModConfigSpec.IntValue DISCORD_RETRY_BASE_DELAY_MILLIS = BUILDER
            .comment("Base delay in milliseconds before the first Discord webhook retry; doubles per retry (100-5000).",
                    "A Retry-After header from Discord takes precedence and is capped at 60000 ms.")
            .defineInRange("discordRetryBaseDelayMillis", 1000, 100, 5000);

    public static final ModConfigSpec.IntValue DISCORD_BATCH_WINDOW_MILLIS = BUILDER
            .comment("Realtime Discord death notifications in the same window are combined into one message (0-10000 ms).",
                    "Default 1500 ms; 0 disables batching and restores one message per death.")
            .defineInRange("discordBatchWindowMillis", 1500, 0, 10000);

    public static final ModConfigSpec.IntValue DISCORD_BATCH_MAX_DEATHS = BUILDER
            .comment("Maximum number of death lines shown in one batched realtime Discord message (1-25).",
                    "Additional deaths in the same window are summarized as an \"…and N more\" suffix.")
            .defineInRange("discordBatchMaxDeaths", 10, 1, 25);

    public static final ModConfigSpec.BooleanValue INCLUDE_OPTED_OUT_IN_EXPORTS = BUILDER
            .comment("Include players who opted out of public listings in operator exports.")
            .define("includeOptedOutInExports", false);

    public static final ModConfigSpec.BooleanValue AUTO_BACKUP_ENABLED = BUILDER
            .comment("Automatically back up the death data to <world>/deathcensus/backups/ on server stop and on hourly world saves.")
            .define("autoBackupEnabled", true);

    public static final ModConfigSpec.IntValue AUTO_BACKUP_GENERATIONS = BUILDER
            .comment("Maximum number of automatic backup files to keep per world (1-20).")
            .defineInRange("autoBackupGenerations", 5, 1, 20);

    public static final ModConfigSpec.BooleanValue DEV_MODE = BUILDER
            .comment("Development only — never enable on a production server.",
                    "Enables /dc dev demo when the JVM flag -Ddeathcensus.dev=true is also set.",
                    "Both gates are required; the JVM flag cannot be toggled at runtime.")
            .define("devMode", false);

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    static boolean validateTimeZone(Object obj) {
        if (!(obj instanceof String s)) {
            return false;
        }
        if (s.isBlank()) {
            return true;
        }
        try {
            ZoneId.of(s);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    static boolean validateLanguage(Object obj) {
        return obj instanceof String s && ServerLang.BUNDLED_LANGUAGES.contains(s);
    }

    static boolean validateDimensionId(Object obj) {
        return obj instanceof String s && !s.isBlank() && s.chars().noneMatch(Character::isWhitespace);
    }

    static boolean validatePvpMode(Object obj) {
        return obj instanceof String s && DiscordLogic.PvpFilterMode.fromConfigValue(s) != null;
    }

    static boolean validateAwardsPeriod(Object obj) {
        return obj instanceof String s && (s.equals("today") || s.equals("7d")
                || s.equals("30d") || s.equals("all"));
    }

    public static ZoneId displayTimeZone() {
        try {
            String v = DISPLAY_TIME_ZONE.get();
            if (v == null || v.isBlank()) {
                return ZoneId.systemDefault();
            }
            return ZoneId.of(v);
        } catch (RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }

    public static boolean enabled() {
        try {
            return ENABLED.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    public static boolean recordCreativeDeaths() {
        try {
            return RECORD_CREATIVE_DEATHS.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    public static boolean milestoneAnnouncements() {
        try {
            return MILESTONE_ANNOUNCEMENTS.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    /** Witness capture is on by default: historical witnesses can never be backfilled. */
    public static boolean captureWitnesses() {
        try {
            return CAPTURE_WITNESSES.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    public static int witnessRadiusBlocks() {
        try {
            return WITNESS_RADIUS_BLOCKS.get();
        } catch (IllegalStateException notLoaded) {
            return 24;
        }
    }

    /** Zero is the escape hatch: no scan and no stored witness list. */
    public static int witnessMaxPerDeath() {
        try {
            return WITNESS_MAX_PER_DEATH.get();
        } catch (IllegalStateException notLoaded) {
            return 8;
        }
    }

    /** False before config load and by default; Discord content is unchanged by PR-2. */
    public static boolean witnessesInDiscord() {
        try {
            return WITNESSES_IN_DISCORD.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    /** Context capture is on by default: missing context can never be backfilled. */
    public static boolean captureDeathContext() {
        try {
            return CAPTURE_DEATH_CONTEXT.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    /** Bounded effect list length (0-16); zero disables effect storage. */
    public static int deathContextMaxEffects() {
        try {
            return DEATH_CONTEXT_MAX_EFFECTS.get();
        } catch (IllegalStateException notLoaded) {
            return 8;
        }
    }

    /** Realtime Discord content stays unchanged; accessor is the opt-in seam. */
    public static boolean deathContextInDiscord() {
        try {
            return DEATH_CONTEXT_IN_DISCORD.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    /** Public exports include context by default; backups always include it. */
    public static boolean deathContextInExports() {
        try {
            return DEATH_CONTEXT_IN_EXPORTS.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    /** Chronicle recording is on by default; the cap bounds stored history. */
    public static boolean chronicleEnabled() {
        try {
            return CHRONICLE_ENABLED.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    public static int chronicleMaxEvents() {
        try {
            return Math.max(0, Math.min(1000, CHRONICLE_MAX_EVENTS.get()));
        } catch (IllegalStateException notLoaded) {
            return 200;
        }
    }

    public static boolean chronicleInExport() {
        try {
            return CHRONICLE_IN_EXPORT.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    /** No new Discord posts today; this is the opt-in seam for later. */
    public static boolean chronicleInDiscord() {
        try {
            return CHRONICLE_IN_DISCORD.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    /** Awards default on; every surface re-checks this accessor at use time. */
    public static boolean awardsEnabled() {
        try {
            return AWARDS_ENABLED.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    /** Validated period literal, defaulting to the §15 Q6 recommendation. */
    public static String awardsDefaultPeriod() {
        try {
            String value = AWARDS_DEFAULT_PERIOD.get();
            return validateAwardsPeriod(value) ? value : "all";
        } catch (IllegalStateException notLoaded) {
            return "all";
        }
    }

    public static boolean awardsInDigest() {
        try {
            return AWARDS_IN_DIGEST.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    public static int awardsMinCount() {
        try {
            return Math.max(1, Math.min(100, AWARDS_MIN_COUNT.get()));
        } catch (IllegalStateException notLoaded) {
            return 1;
        }
    }

    public static boolean nemesisEnabled() {
        try {
            return NEMESIS_ENABLED.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    public static int nemesisMinMobCount() {
        try {
            return Math.max(1, Math.min(100, NEMESIS_MIN_MOB_COUNT.get()));
        } catch (IllegalStateException notLoaded) {
            return 3;
        }
    }

    public static int nemesisMinNamedCount() {
        try {
            return Math.max(1, Math.min(100, NEMESIS_MIN_NAMED_COUNT.get()));
        } catch (IllegalStateException notLoaded) {
            return 2;
        }
    }

    /** No new Discord posts in PR-6; this accessor is the opt-in seam. */
    public static boolean nemesisInDigest() {
        try {
            return NEMESIS_IN_DIGEST.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    public static int maxRecordsPerPlayer() {
        try {
            return MAX_RECORDS_PER_PLAYER.get();
        } catch (IllegalStateException notLoaded) {
            return 10000;
        }
    }

    public static String discordWebhookUrl() {
        try {
            String v = DISCORD_WEBHOOK_URL.get();
            return v != null ? v : "";
        } catch (IllegalStateException notLoaded) {
            return "";
        }
    }

    public static String discordDigestWebhookUrl() {
        try {
            String v = DISCORD_DIGEST_WEBHOOK_URL.get();
            return v != null ? v : "";
        } catch (IllegalStateException notLoaded) {
            return "";
        }
    }

    public static boolean discordNotifyDeaths() {
        try {
            return DISCORD_NOTIFY_DEATHS.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    /** Realtime death dimension allow-list; empty means every dimension. */
    public static List<String> discordNotifyDimensions() {
        try {
            List<? extends String> value = DISCORD_NOTIFY_DIMENSIONS.get();
            return value != null ? List.copyOf(value) : List.of();
        } catch (IllegalStateException notLoaded) {
            return List.of();
        }
    }

    /** Realtime death PvP mode; defaults to {@code all} before config load. */
    public static DiscordLogic.PvpFilterMode discordNotifyPvpMode() {
        try {
            String value = DISCORD_NOTIFY_PVP_MODE.get();
            DiscordLogic.PvpFilterMode mode = DiscordLogic.PvpFilterMode.fromConfigValue(value);
            return mode != null ? mode : DiscordLogic.PvpFilterMode.ALL;
        } catch (IllegalStateException notLoaded) {
            return DiscordLogic.PvpFilterMode.ALL;
        }
    }

    public static String discordLanguage() {
        try {
            String v = DISCORD_LANGUAGE.get();
            return v != null ? v : "en_us";
        } catch (IllegalStateException notLoaded) {
            return "en_us";
        }
    }

    public static boolean discordDigestEnabled() {
        try {
            return DISCORD_DIGEST_ENABLED.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    public static int discordDigestIntervalDays() {
        try {
            return DISCORD_DIGEST_INTERVAL_DAYS.get();
        } catch (IllegalStateException notLoaded) {
            return 7;
        }
    }

    public static int discordRetryMaxAttempts() {
        try {
            return DISCORD_RETRY_MAX_ATTEMPTS.get();
        } catch (IllegalStateException notLoaded) {
            return 4;
        }
    }

    public static int discordRetryBaseDelayMillis() {
        try {
            return DISCORD_RETRY_BASE_DELAY_MILLIS.get();
        } catch (IllegalStateException notLoaded) {
            return 1000;
        }
    }

    /** Batch window in milliseconds; 0 means one webhook POST per death (§2). */
    public static int discordBatchWindowMillis() {
        try {
            return DISCORD_BATCH_WINDOW_MILLIS.get();
        } catch (IllegalStateException notLoaded) {
            return 1500;
        }
    }

    /** Maximum rendered death lines in one batch; the rest become a "+N more" suffix. */
    public static int discordBatchMaxDeaths() {
        try {
            return DISCORD_BATCH_MAX_DEATHS.get();
        } catch (IllegalStateException notLoaded) {
            return 10;
        }
    }

    public static boolean includeOptedOutInExports() {
        try {
            return INCLUDE_OPTED_OUT_IN_EXPORTS.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    public static boolean autoBackupEnabled() {
        try {
            return AUTO_BACKUP_ENABLED.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    public static int autoBackupGenerations() {
        try {
            return AUTO_BACKUP_GENERATIONS.get();
        } catch (IllegalStateException notLoaded) {
            return 5;
        }
    }

    /** §8 double gate half 1; always false before config load. Never defaulted on. */
    public static boolean devMode() {
        try {
            return DEV_MODE.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    static List<String> bundledLanguages() {
        return ServerLang.BUNDLED_LANGUAGES;
    }
}
