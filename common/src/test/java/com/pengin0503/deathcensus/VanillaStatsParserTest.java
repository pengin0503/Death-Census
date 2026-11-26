package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PR-10 (DESIGN 1.3.0 §9): tolerant parser fixtures for vanilla stat files. */
class VanillaStatsParserTest {
    private static final String REAL_SHAPE = """
            {
              "stats": {
                "minecraft:custom": {
                  "minecraft:leave_game": 2,
                  "minecraft:deaths": 7,
                  "minecraft:killed_by": {
                    "minecraft:player": 3,
                    "minecraft:spit": 2,
                    "minecraft:falling_block": 1
                  }
                },
                "minecraft: mined": { "minecraft:stone": 100 }
              },
              "DataVersion": 3955
            }
            """;

    @Test
    void readsRealWorldNestedShape() {
        VanillaStats stats = VanillaStatsParser.parse(REAL_SHAPE);
        assertEquals(7, stats.deaths());
        assertEquals(Map.of("minecraft:player", 3, "minecraft:spit", 2,
                "minecraft:falling_block", 1), stats.killedBy());
        assertFalse(stats.isEmpty());
    }

    @Test
    void missingCustomKeysAreZeroAndEmpty() {
        VanillaStats stats = VanillaStatsParser.parse("{\"stats\":{\"minecraft:custom\":{}},\"DataVersion\":1}");
        assertEquals(0, stats.deaths());
        assertTrue(stats.killedBy().isEmpty());
        assertTrue(stats.isEmpty());
        assertEquals(VanillaStats.EMPTY, VanillaStatsParser.parse("{}"));
        assertEquals(VanillaStats.EMPTY, VanillaStatsParser.parse(""));
        assertEquals(VanillaStats.EMPTY, VanillaStatsParser.parse(null));
    }

    @Test
    void numericStringsAndOddValuesAreTolerated() {
        VanillaStats stats = VanillaStatsParser.parse("""
                {"stats":{"minecraft:custom":{"minecraft:deaths":"12",
                "minecraft:killed_by":{"minecraft:creeper":"4","minecraft:zombie":-3,
                "minecraft:spider":true,"minecraft:enderman":null}}}}
                """);
        assertEquals(12, stats.deaths());
        assertEquals(4, stats.killedBy().get("minecraft:creeper"));
        assertFalse(stats.killedBy().containsKey("minecraft:zombie"));
        assertFalse(stats.killedBy().containsKey("minecraft:spider"));
        assertFalse(stats.killedBy().containsKey("minecraft:enderman"));
    }

    @Test
    void malformedInputAndTrailingJunkReturnEmpty() {
        for (String bad : List.of("{\"stats\":", "{\"stats\":{} garbage}", "not json", "[]",
                "{\"stats\":{\"minecraft:custom\":{\"minecraft:deaths\": -1}}}")) {
            assertEquals(VanillaStats.EMPTY, VanillaStatsParser.parse(bad), bad);
        }
    }

    @Test
    void entityIdsMapToCategoriesWithPlayerOverride() {
        Map<String, Integer> causes = VanillaStatsParser.causeCounts(VanillaStatsParser.parse(REAL_SHAPE));
        assertEquals(3, causes.get("player"), "minecraft:player must not classify as a mob");
        assertEquals(2, causes.get("mob"));
        assertEquals(1, causes.get("generic"));
        assertEquals("player", VanillaStatsParser.topCause(VanillaStatsParser.parse(REAL_SHAPE)));
    }

    @Test
    void multipleIdsOfOneCategoryAreSummedAndUnknownIdsBecomeOther() {
        VanillaStats stats = VanillaStatsParser.parse("""
                {"stats":{"minecraft:custom":{"minecraft:killed_by":{
                "minecraft:player":2,"minecraft:spit":4,"minecraft:mob_attack":1,
                "minecraft:zombie":3,"minecraft:custom_entity":5}}}}
                """);
        Map<String, Integer> causes = VanillaStatsParser.causeCounts(stats);
        assertEquals(2, causes.get("player"));
        assertEquals(5, causes.get("mob"), "two vanilla mob ids collapse into one category");
        assertEquals(8, causes.get("other"));
        assertEquals("other", VanillaStatsParser.topCause(stats));
    }

    @Test
    void onlyStatisticsAreReadNamesAreIgnored() {
        VanillaStats stats = VanillaStatsParser.parse("""
                {"name":"Alice","playerName":"Alice","stats":{"minecraft:custom":{
                "minecraft:deaths":1,"minecraft:killed_by":{"minecraft:player":1}}}}
                """);
        assertEquals(1, stats.deaths());
        assertEquals(Map.of("minecraft:player", 1), stats.killedBy());
        assertFalse(stats.toString().contains("Alice"));
    }
}
