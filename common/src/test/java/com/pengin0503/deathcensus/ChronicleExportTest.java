package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.4.0 §3: the public chronicle section is opt-in and escaped in every export. */
class ChronicleExportTest {
    private static List<PlayerDeaths> players() {
        return List.of(new PlayerDeaths("u-a", "Alice", false, List.of(new DeathEntry(
                "u-a", "Alice", 1_000L, 1L, 10, 10, "d", 0, 0, 0,
                "fall", "minecraft:fall", "", "", "", "survival", 0, "", "", 0))));
    }

    private static final List<String> LINES = List.of("First death · Anonymous · day 1");

    @Test
    void defaultRenderersHaveNoChronicleSection() {
        assertFalse(ExportRenderers.toCsv(players()).contains("chronicle_section"));
        assertFalse(ExportRenderers.toJson(players(), 2_000L).contains("\"chronicle\""));
        assertFalse(ExportRenderers.toHtml(players(), 2_000L, ZoneOffset.UTC)
                .contains("<h2>Chronicle</h2>"));
    }

    @Test
    void chronicleSectionsAreRenderedInAllThreeFormats() {
        String csv = ExportRenderers.toCsvWithChronicle(
                players(), false, true, null, List.of(), "", LINES, "Chronicle");
        assertTrue(csv.contains("chronicle_section,chronicle_line"));
        assertTrue(csv.contains("First death · Anonymous"));

        String json = ExportRenderers.toJsonWithChronicle(
                players(), 2_000L, false, true, null, List.of(), "", LINES, "Chronicle");
        assertTrue(json.contains("\"chronicle\""));
        assertTrue(json.contains("\"section\": \"Chronicle\""));
        assertTrue(json.contains("First death · Anonymous"));
        assertFalse(json.contains("\"chronicleEvents\""));

        String html = ExportRenderers.toHtmlWithChronicle(players(), 2_000L, ZoneOffset.UTC,
                false, true, "Context", null, List.of(), "", LINES, "Chronicle");
        assertTrue(html.contains("<h2>Chronicle</h2>"));
        assertTrue(html.contains("First death · Anonymous"));
    }

    @Test
    void chronicleTextIsEscaped() {
        String html = ExportRenderers.toHtmlWithChronicle(players(), 2_000L, ZoneOffset.UTC,
                false, true, "Context", null, List.of(), "",
                List.of("<script>alert(1)</script>"), "Chronicle");
        assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains("<script>"));
    }
}
