package com.pengin0503.deathcensus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * Pure, deterministic sample-death generator for the gated {@code /dc dev demo}
 * command (DESIGN 1.3.0 §8). Given the same {@code (count, epochNow, zone, seed)}
 * it always returns the same {@link DeathEntry} list; it has no server, storage,
 * Discord or milestone dependencies.
 *
 * <p>Every name is a synthetic {@code Demo Player NN} label and every UUID is
 * derived from a fixed namespace string, so generated records can never be
 * mistaken for a real online player's identity.
 */
public final class DemoDataGenerator {
    public static final int MIN_COUNT = 1;
    public static final int MAX_COUNT = 500;
    /** Fixed seed used by the command: one count always produces one data set. */
    public static final long DEFAULT_SEED = 0x4445434F44454D4FL;

    private static final String UUID_NAMESPACE = "deathcensus-demo-v1";
    private static final String NAME_PREFIX = "Demo Player ";
    private static final long HOUR_MILLIS = 3_600_000L;
    private static final long DAY_MILLIS = 86_400_000L;
    private static final List<String> DIMENSIONS = List.of(
            "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");
    /** Cause ids aligned one-to-one with {@link CauseClassifier#CATEGORIES}. */
    private static final List<String> CAUSE_IDS = List.of(
            "minecraft:fall", "minecraft:drown", "minecraft:in_fire", "minecraft:lava",
            "minecraft:lightning_bolt", "minecraft:freeze", "minecraft:starve",
            "minecraft:in_wall", "minecraft:cactus", "minecraft:explosion",
            "minecraft:magic", "minecraft:arrow", "minecraft:player_attack",
            "minecraft:mob_attack", "minecraft:out_of_world", "minecraft:generic",
            "minecraft:unknown");

    private DemoDataGenerator() {
    }

    /**
     * Generates deterministic sample records. Bounds are clamped to
     * {@link #MIN_COUNT}..{@link #MAX_COUNT} (Brigadier also enforces them).
     */
    public static List<DeathEntry> generate(int count, long epochNow, ZoneId zone, long seed) {
        int effectiveCount = Math.max(MIN_COUNT, Math.min(MAX_COUNT, count));
        ZoneId effectiveZone = zone != null ? zone : ZoneOffset.UTC;
        Random seeded = new Random(seed);
        List<DeathEntry> out = new ArrayList<>(effectiveCount);
        long windowStart = epochNow - (long) effectiveCount * HOUR_MILLIS;

        for (int i = 0; i < effectiveCount; i++) {
            long epochMillis = Math.max(1L, windowStart + i * HOUR_MILLIS + seeded.nextInt((int) HOUR_MILLIS));
            long offsetMillis = effectiveZone.getRules()
                    .getOffset(Instant.ofEpochMilli(epochMillis)).getTotalSeconds() * 1000L;
            long gameDay = Math.floorDiv(epochMillis + offsetMillis, DAY_MILLIS);

            String name = NAME_PREFIX + String.format(Locale.ROOT, "%02d", i + 1);
            String uuid = UUID.nameUUIDFromBytes(
                    (UUID_NAMESPACE + ":" + (i + 1)).getBytes(StandardCharsets.UTF_8)).toString();
            String category = CauseClassifier.CATEGORIES.get(i % CauseClassifier.CATEGORIES.size());
            String causeId = CAUSE_IDS.get(i % CAUSE_IDS.size());
            String dimension = DIMENSIONS.get(i % DIMENSIONS.size());
            boolean playerKiller = "player".equals(category);
            boolean mobKiller = "mob".equals(category);

            double x = Math.round((seeded.nextDouble() * 600.0 - 300.0) * 100.0) / 100.0;
            double z = Math.round((seeded.nextDouble() * 600.0 - 300.0) * 100.0) / 100.0;
            double y = switch (dimension) {
                case "minecraft:the_nether" -> 5.0 + seeded.nextInt(110);
                case "minecraft:the_end" -> 40.0 + seeded.nextInt(80);
                default -> -40.0 + seeded.nextInt(160);
            };

            int playTicks = 20 * (3_600 + seeded.nextInt(2_592_000));
            int survivalTicks = Math.max(1, playTicks - 20 * (60 + seeded.nextInt(21_600)));
            int experienceLevel = seeded.nextInt(30);
            int fCount = i % 3 == 0 ? 1 + seeded.nextInt(3) : 0;
            String excuse = i % 7 == 0 ? "Demo excuse " + (i + 1) : "";
            String lastWords = i % 5 == 0 ? "Demo last words " + (i + 1) : "";
            String killerType = playerKiller ? "minecraft:player"
                    : mobKiller ? "minecraft:zombie" : "";
            String killerName = mobKiller ? "Demo Zombie" : "";
            String message = playerKiller || mobKiller
                    ? name + " was slain by a demo " + category
                    : name + " met a demo " + category + " end";

            out.add(new DeathEntry(
                    uuid, name, epochMillis, gameDay,
                    playTicks, survivalTicks, dimension, x, y, z,
                    category, causeId, message, killerType, killerName,
                    "survival", experienceLevel, excuse, lastWords, fCount));
        }
        return List.copyOf(out);
    }

    /** True for the synthetic names this generator emits (never a real player). */
    public static boolean isDemoName(String name) {
        if (name == null || !name.startsWith(NAME_PREFIX)) {
            return false;
        }
        String suffix = name.substring(NAME_PREFIX.length());
        if (suffix.isEmpty() || suffix.length() > 3) {
            return false;
        }
        for (int i = 0; i < suffix.length(); i++) {
            if (suffix.charAt(i) < '0' || suffix.charAt(i) > '9') {
                return false;
            }
        }
        return Integer.parseInt(suffix) >= 1;
    }

    /** True when the entry is identifiable as generated demo data. */
    public static boolean isDemoRecord(DeathEntry entry) {
        return entry != null && isDemoName(entry.playerName());
    }
}
