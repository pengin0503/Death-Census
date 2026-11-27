package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.4.0 §2: awards sections are opt-in and escaped in every export renderer. */
class AwardsExportTest {
    private static List<PlayerDeaths> players() {
        return List.of(new PlayerDeaths("u-a", "Alice", false, List.of(new DeathEntry(
                "u-a", "Alice", 1_000L, 1L, 10, 10, "d", 0, 0, 0,
                "fall", "minecraft:fall", "", "", "", "survival", 0, "", "", 0))));
    }

    private static final List<String> LINES = List.of("Most deaths: Alice — 1");

    @Test
    void defaultRenderersHaveNoAwardsSection() {
        assertFalse(ExportRenderers.toCsv(players()).contains("award_section"));
        assertFalse(ExportRenderers.toJson(players(), 2_000L).contains("\"awards\""));
        assertFalse(ExportRenderers.toHtml(players(), 2_000L, ZoneOffset.UTC).contains("<h2>Awards</h2>"));
    }

    @Test
    void awardsSectionsAreRenderedInAllThreeFormats() {
        String csv = ExportRenderers.toCsv(players(), false, true, null, LINES, "Awards");
        assertTrue(csv.contains("award_section,award_line"));
        assertTrue(csv.contains("Most deaths: Alice"));

        String json = ExportRenderers.toJson(players(), 2_000L, false, true, null, LINES, "Awards");
        assertTrue(json.contains("\"awards\""));
        assertTrue(json.contains("\"section\": \"Awards\""));
        assertTrue(json.contains("Most deaths: Alice"));

        String html = ExportRenderers.toHtml(
                players(), 2_000L, ZoneOffset.UTC, false, true, "Context", null, LINES, "Awards");
        assertTrue(html.contains("<h2>Awards</h2>"));
        assertTrue(html.contains("Most deaths: Alice"));
    }

    @Test
    void awardTextIsEscaped() {
        String html = ExportRenderers.toHtml(players(), 2_000L, ZoneOffset.UTC, false, true,
                "Context", null, List.of("<script>alert(1)</script>"), "Awards");
        assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains("<script>"));
    }
}
