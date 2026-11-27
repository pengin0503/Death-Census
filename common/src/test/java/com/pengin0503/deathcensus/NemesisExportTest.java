package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.4.0 §4: the nemesis section is opt-in and escaped in every export renderer. */
class NemesisExportTest {
    private static List<PlayerDeaths> players() {
        return List.of(new PlayerDeaths("u-a", "Alice", false, List.of(new DeathEntry(
                "u-a", "Alice", 1_000L, 1L, 10, 10, "d", 0, 0, 0,
                "player", "minecraft:player", "", "minecraft:player", "Steve",
                "survival", 0, "", "", 0))));
    }

    private static final List<String> LINES = List.of("PvP rival: Steve — 1 kill(s)");

    @Test
    void defaultRenderersHaveNoNemesisSection() {
        assertFalse(ExportRenderers.toCsv(players()).contains("nemesis_section"));
        assertFalse(ExportRenderers.toJson(players(), 2_000L).contains("\"nemesis\""));
        assertFalse(ExportRenderers.toHtml(players(), 2_000L, ZoneOffset.UTC)
                .contains("<h2>Nemesis</h2>"));
    }

    @Test
    void nemesisSectionsAreRenderedInAllThreeFormats() {
        String csv = ExportRenderers.toCsvWithNemesis(players(), false, true, null,
                List.of(), "", List.of(), "", LINES, "Nemesis");
        assertTrue(csv.contains("nemesis_section,nemesis_line"));
        assertTrue(csv.contains("PvP rival: Steve"));

        String json = ExportRenderers.toJsonWithNemesis(players(), 2_000L, false, true, null,
                List.of(), "", List.of(), "", LINES, "Nemesis");
        assertTrue(json.contains("\"nemesis\""));
        assertTrue(json.contains("\"section\": \"Nemesis\""));
        assertTrue(json.contains("PvP rival: Steve"));

        String html = ExportRenderers.toHtmlWithNemesis(players(), 2_000L, ZoneOffset.UTC,
                false, true, "Context", null, List.of(), "", List.of(), "", LINES, "Nemesis");
        assertTrue(html.contains("<h2>Nemesis</h2>"));
        assertTrue(html.contains("PvP rival: Steve"));
    }

    @Test
    void nemesisTextIsEscaped() {
        String html = ExportRenderers.toHtmlWithNemesis(players(), 2_000L, ZoneOffset.UTC,
                false, true, "Context", null, List.of(), "", List.of(), "",
                List.of("<script>alert(1)</script>"), "Nemesis");
        assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains("<script>"));

        String json = ExportRenderers.toJsonWithNemesis(players(), 2_000L, false, true, null,
                List.of(), "", List.of(), "", List.of("a\"b"), "Nemesis");
        assertTrue(json.contains("a\\\"b"));
    }
}
