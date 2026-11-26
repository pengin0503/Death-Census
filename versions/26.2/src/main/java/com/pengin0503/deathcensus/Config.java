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

    static List<String> bundledLanguages() {
        return ServerLang.BUNDLED_LANGUAGES;
    }
}
