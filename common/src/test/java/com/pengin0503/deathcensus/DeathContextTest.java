package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN 1.4.0 §11: pure death-context shaping, bounds and escaping. */
class DeathContextTest {

    @Test
    void emptyIsAnImmutableSingletonUsedForAbsentContext() {
        assertSame(DeathContext.EMPTY,
                new DeathEntry("u", "N", 1L, 0L, 0, 0, "d", 0, 0, 0,
                        "c", "id", "msg", "", "", "mode", 1, "", "", 0).context());
        assertTrue(DeathContext.EMPTY.isEmpty());
        assertEquals(new DeathContext("", List.of(), List.of(), "", ""), DeathContext.EMPTY);
        assertFalse(new DeathContext("minecraft:sword", List.of(), List.of(), "", "").isEmpty());
    }

    @Test
    void nullAndBlankIdsAreNormalizedWithoutFabricatingValues() {
        List<String> armor = new ArrayList<>(java.util.Arrays.asList(null, "  ", " minecraft:diamond_boots "));
        DeathContext c = DeathContext.of("  minecraft:diamond_sword ", armor,
                java.util.Arrays.asList(null, "", "minecraft:speed:1"),
                "  minecraft:plains  ", " thunder ", 8);
        armor.clear();

        assertEquals("minecraft:diamond_sword", c.mainHand());
        assertEquals(List.of("minecraft:diamond_boots"), c.armor());
        assertEquals(List.of("minecraft:speed:1"), c.effects());
        assertEquals("minecraft:plains", c.biome());
        assertEquals("thunder", c.weather());
        assertEquals("", DeathContext.clean(null));
        assertEquals("", DeathContext.clean(" \t "));
    }

    @Test
    void armorIsCappedAtFourSlotsAndEffectsAtTheConfiguredMaximum() {
        DeathContext c = DeathContext.of("main",
                List.of("helm", "chest", "legs", "boots", "extra1", "extra2"),
                List.of("e1", "e2", "e3", "e4", "e5", "e6", "e7", "e8", "e9"),
                "biome", "rain", 3);
        assertEquals(DeathContext.MAX_ARMOR, c.armor().size());
        assertEquals(List.of("helm", "chest", "legs", "boots"), c.armor());
        assertEquals(List.of("e1", "e2", "e3"), c.effects());
        assertEquals(List.of(), DeathContext.of("m", List.of("a"), List.of("e"), "b", "clear", 0).effects());
    }

    @Test
    void canonicalConstructorAppliesHardBoundsForHostileCallers() {
        List<String> many = java.util.stream.IntStream.range(0, 40)
                .mapToObj(i -> "effect:" + i).toList();
        DeathContext c = new DeathContext("m", many, many, "b", "rain");
        assertEquals(DeathContext.MAX_ARMOR, c.armor().size());
        assertEquals(DeathContext.HARD_MAX_EFFECTS, c.effects().size());
        assertEquals(8, DeathContext.DEFAULT_MAX_EFFECTS);
        assertEquals(0, DeathContext.clampMaxEffects(-5));
        assertEquals(16, DeathContext.clampMaxEffects(500));
    }

    @Test
    void weatherIsRestrictedToTheThreeSpecifiedWords() {
        assertEquals("clear", DeathContext.cleanWeather("clear"));
        assertEquals("rain", DeathContext.cleanWeather("rain"));
        assertEquals("thunder", DeathContext.cleanWeather("thunder"));
        assertEquals("", DeathContext.cleanWeather("sunny"));
        assertEquals("", new DeathContext("m", List.of(), List.of(), "b", "hail").weather());
    }

    @Test
    void biomeRedactionCopyLeavesTheOriginalUntouched() {
        DeathContext original = new DeathContext("m", List.of("a"), List.of("e"), "minecraft:plains", "clear");
        DeathContext hidden = original.withBiome(DeathRedactor.COORDINATE_MARKER);
        assertNotSame(original, hidden);
        assertEquals("minecraft:plains", original.biome());
        assertEquals(DeathRedactor.COORDINATE_MARKER, hidden.biome());
        assertEquals(original.mainHand(), hidden.mainHand());
        assertEquals(original.armor(), hidden.armor());
        assertEquals(original.effects(), hidden.effects());
        assertEquals(original.weather(), hidden.weather());
    }

    @Test
    void idsAreEscapedByRenderersNotEmbeddedAsTrustedMarkup() {
        DeathContext c = new DeathContext("<sword>&\"", List.of("arm,or"), List.of("eff\"ect"),
                "<biome>", "clear");
        String html = ExportRenderers.toHtml(List.of(new PlayerDeaths("u", "P", false,
                List.of(new DeathEntry("u", "P", 1L, 0L, 0, 0, "d", 0, 0, 0,
                        "c", "id", "msg", "", "", "mode", 1, "", "", 0, List.of(), c)))),
                2L, java.time.ZoneOffset.UTC);
        assertTrue(html.contains("&lt;sword&gt;&amp;&quot;"), html);
        assertTrue(html.contains("arm,or"), html);
        assertFalse(html.contains("<sword>"), html);

        String csv = ExportRenderers.toCsv(List.of(new PlayerDeaths("u", "P", false,
                List.of(new DeathEntry("u", "P", 1L, 0L, 0, 0, "d", 0, 0, 0,
                        "c", "id", "msg", "", "", "mode", 1, "", "", 0, List.of(), c)))));
        assertTrue(csv.contains("\"arm,or\""), csv);
        assertTrue(csv.contains("\"eff\"\"ect\""), csv);
    }

    @Test
    void contextIsBuiltFromRegistryIdsOnlyNeverDisplayNames() {
        DeathContext c = DeathContext.of("minecraft:diamond_sword",
                List.of("minecraft:diamond_helmet"), List.of("minecraft:speed:1"),
                "minecraft:plains", "clear", 8);
        String text = ExportRenderers.contextText(c);
        assertTrue(text.contains("minecraft:diamond_sword"), text);
        assertFalse(text.contains("display"), text);
        assertTrue(text.contains("Main hand: minecraft:diamond_sword"), text);
        assertEquals("", ExportRenderers.contextText(DeathContext.EMPTY));
    }
}
