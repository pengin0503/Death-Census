package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN 1.3.0 §8: deterministic sample data with no real-player identities. */
class DemoDataGeneratorTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final ZoneId ZONE = ZoneOffset.UTC;

    @Test
    void sameSeedProducesIdenticalDataSet() {
        List<DeathEntry> first = DemoDataGenerator.generate(20, NOW, ZONE, 42L);
        List<DeathEntry> second = DemoDataGenerator.generate(20, NOW, ZONE, 42L);
        assertEquals(first, second);
        assertEquals(first.toString(), second.toString());
    }

    @Test
    void differentSeedProducesDifferentDataSet() {
        List<DeathEntry> first = DemoDataGenerator.generate(20, NOW, ZONE, 42L);
        List<DeathEntry> second = DemoDataGenerator.generate(20, NOW, ZONE, 43L);
        assertNotEquals(first, second);
    }

    @Test
    void countIsBounded() {
        assertEquals(1, DemoDataGenerator.generate(0, NOW, ZONE, 1L).size());
        assertEquals(1, DemoDataGenerator.generate(-5, NOW, ZONE, 1L).size());
        assertEquals(500, DemoDataGenerator.generate(501, NOW, ZONE, 1L).size());
        assertTrue(DemoDataGenerator.DEFAULT_SEED != 0L);
    }

    @Test
    void allSeventeenCategoriesCoveredWhenCountReachesSeventeen() {
        Set<String> categories = new HashSet<>();
        for (DeathEntry entry : DemoDataGenerator.generate(17, NOW, ZONE, 7L)) {
            categories.add(entry.causeCategory());
            assertTrue(CauseClassifier.isKnownCategory(entry.causeCategory()));
        }
        assertEquals(Set.copyOf(CauseClassifier.CATEGORIES), categories);
    }

    @Test
    void recordsUseSyntheticNamesAndFixedNamespaceUuids() {
        List<DeathEntry> entries = DemoDataGenerator.generate(30, NOW, ZONE, 9L);
        Set<String> uuids = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            DeathEntry entry = entries.get(i);
            assertEquals(String.format(java.util.Locale.ROOT, "Demo Player %02d", i + 1), entry.playerName());
            assertTrue(DemoDataGenerator.isDemoName(entry.playerName()));
            assertTrue(DemoDataGenerator.isDemoRecord(entry));
            assertEquals(3, UUID.fromString(entry.playerUuid()).version());
            assertTrue(uuids.add(entry.playerUuid()), "UUIDs must be unique");
            assertFalse(DemoDataGenerator.isDemoName("Notch"));
        }
        // Regenerating with the same seed keeps the name -> UUID mapping stable.
        assertEquals(entries.get(0).playerUuid(),
                DemoDataGenerator.generate(30, NOW, ZONE, 9L).get(0).playerUuid());
    }

    @Test
    void fieldsArePlausibleAndBounded() {
        List<DeathEntry> entries = DemoDataGenerator.generate(25, NOW, ZONE, 11L);
        Set<String> dimensions = new HashSet<>();
        int withLastWords = 0;
        int withExcuse = 0;
        int withF = 0;
        for (int i = 0; i < entries.size(); i++) {
            DeathEntry e = entries.get(i);
            assertNotNull(e.playerUuid());
            assertNotNull(e.playerName());
            assertNotNull(e.dimension());
            assertNotNull(e.causeCategory());
            assertNotNull(e.causeId());
            assertNotNull(e.deathMessage());
            assertNotNull(e.killerType());
            assertNotNull(e.killerName());
            assertNotNull(e.gameMode());
            assertNotNull(e.excuse());
            assertNotNull(e.lastWords());
            assertFalse(e.deathMessage().isEmpty());
            assertFalse(e.causeId().isEmpty());
            assertTrue(e.playTicks() > 0 && e.survivalTicks() > 0);
            assertTrue(e.experienceLevel() >= 0 && e.experienceLevel() < 30);
            assertTrue(e.x() >= -300.0 && e.x() <= 300.0);
            assertTrue(e.z() >= -300.0 && e.z() <= 300.0);
            assertTrue(e.y() >= -40.0 && e.y() < 160.0);
            assertTrue(e.excuse().length() <= 100 && e.lastWords().length() <= 100);
            dimensions.add(e.dimension());
            if (!e.lastWords().isEmpty()) {
                withLastWords++;
            }
            if (!e.excuse().isEmpty()) {
                withExcuse++;
            }
            if (e.fCount() > 0) {
                withF++;
            }
            assertTrue(e.epochMillis() <= NOW);
            assertTrue(e.epochMillis() > NOW - 26L * 3_600_000L);
            assertEquals(Math.floorDiv(e.epochMillis(), 86_400_000L), e.gameDay());
            if (i > 0) {
                assertTrue(e.epochMillis() > entries.get(i - 1).epochMillis());
            }
        }
        assertEquals(Set.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"), dimensions);
        assertTrue(withLastWords > 0 && withLastWords < entries.size());
        assertTrue(withExcuse > 0 && withExcuse < entries.size());
        assertTrue(withF > 0 && withF < entries.size());
    }

    @Test
    void generatorHasNoNotificationOrStorageSideEffectsByConstruction() throws Exception {
        Path source = Path.of("common/src/main/java/com/pengin0503/deathcensus/DemoDataGenerator.java");
        if (!Files.exists(source)) {
            // Gradle runs tests with the version project as working directory;
            // walk up to the repository root to find the shared source file.
            String sourceDir = System.getProperty("deathcensus.mainSourceDir");
            for (Path p = sourceDir == null ? Path.of(".").toAbsolutePath() : Path.of(sourceDir);
                 p != null; p = p.getParent()) {
                Path candidate = p.resolve("common/src/main/java/com/pengin0503/deathcensus/DemoDataGenerator.java");
                if (Files.exists(candidate)) {
                    source = candidate;
                    break;
                }
            }
        }
        assertTrue(Files.exists(source), "generator source not found: " + source);
        assertPure(Files.readString(source));
    }

    private static void assertPure(String source) {
        assertFalse(source.contains("DiscordNotifier"), "generator must not send Discord messages");
        assertFalse(source.contains("Milestones"), "generator must not evaluate milestones");
        assertFalse(source.contains("notifyDeath"), "generator must not notify deaths");
        assertFalse(source.contains("CensusData"), "generator must not touch storage");
        assertFalse(source.contains("System.currentTimeMillis"),
                "time comes from the epochNow parameter only");
    }
}
