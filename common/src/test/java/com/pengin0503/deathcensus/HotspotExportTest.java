package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.4.0 §5: the hotspot section is opt-in, escaped and never leaks hidden locations. */
class HotspotExportTest {
    private static final String OVERWORLD = "minecraft:overworld";

    private static DeathEntry death(double x, double z, long epoch) {
        return new DeathEntry("p", "Player", epoch, 0L, 0, 0, OVERWORLD, x, 64.0, z,
                "fall", "minecraft:fall", "died", "", "", "survival", 0, "", "", 0);
    }

    private static List<PlayerDeaths> snapshot() {
        return List.of(
                new PlayerDeaths("hidden", "Hidden", false, true, false, false,
                        List.of(death(-300.0, -300.0, 1L))),
                new PlayerDeaths("optout", "OptedOut", true, false, false, false,
                        List.of(death(-500.0, -500.0, 2L))),
                new PlayerDeaths("public", "Public", false, false, false, false,
                        List.of(death(1.0, 1.0, 3L), death(2.0, 2.0, 4L), death(3.0, 3.0, 5L))));
    }

    private static List<String> hotspotLines() {
        return HotspotAnalysis.groupByChunk(snapshot(), null, 0L, 16, 3).stream()
                .map(h -> h.dimension() + " chunk " + h.chunkX() + "," + h.chunkZ()
                        + " — " + h.count() + " · " + h.topCause())
                .toList();
    }

    @Test
    void defaultRenderersHaveNoHotspotSection() {
        assertFalse(ExportRenderers.toCsv(snapshot()).contains("hotspot_section"));
        assertFalse(ExportRenderers.toJson(snapshot(), 2_000L).contains("\"hotspots\""));
        assertFalse(ExportRenderers.toHtml(snapshot(), 2_000L, ZoneOffset.UTC)
                .contains("<h2>Hotspots</h2>"));
    }

    @Test
    void hotspotSectionsAreRenderedInAllThreeFormats() {
        String csv = ExportRenderers.toCsvWithHotspots(snapshot(), false, true, null,
                List.of(), "", List.of(), "", List.of(), "", hotspotLines(), "Hotspots");
        assertTrue(csv.contains("hotspot_section,hotspot_line"));
        assertTrue(csv.contains("chunk 0,0"));

        String json = ExportRenderers.toJsonWithHotspots(snapshot(), 2_000L, false, true, null,
                List.of(), "", List.of(), "", List.of(), "", hotspotLines(), "Hotspots");
        assertTrue(json.contains("\"hotspots\""));
        assertTrue(json.contains("\"section\": \"Hotspots\""));
        assertTrue(json.contains("chunk 0,0"));

        String html = ExportRenderers.toHtmlWithHotspots(snapshot(), 2_000L, ZoneOffset.UTC,
                false, true, "Context", null, List.of(), "", List.of(), "", List.of(), "",
                hotspotLines(), "Hotspots");
        assertTrue(html.contains("<h2>Hotspots</h2>"));
        assertTrue(html.contains("chunk 0,0"));
    }

    @Test
    void anonymizedExportsCarryOnlyPublicCoarseCells() {
        List<String> lines = hotspotLines();
        assertEqualsSingleCell(lines);
        String json = ExportRenderers.toJsonWithHotspots(snapshot(), 2_000L, true, true, snapshot(),
                List.of(), "", List.of(), "", List.of(), "", lines, "Hotspots");
        assertTrue(json.contains("\"anonymized\": true"));
        assertTrue(json.contains("chunk 0,0"));
        assertFalse(json.contains("chunk -19,-19"));
        assertFalse(json.contains("chunk -32,-32"));

        String html = ExportRenderers.toHtmlWithHotspots(snapshot(), 2_000L, ZoneOffset.UTC,
                true, true, "Context", snapshot(), List.of(), "", List.of(), "", List.of(), "",
                lines, "Hotspots");
        assertFalse(html.contains("chunk -19,-19"));
        assertFalse(html.contains("chunk -32,-32"));
    }

    private static void assertEqualsSingleCell(List<String> lines) {
        assertTrue(lines.size() == 1 && lines.get(0).contains("chunk 0,0"),
                "only the public chunk may be present: " + lines);
    }

    @Test
    void hotspotTextIsEscaped() {
        String html = ExportRenderers.toHtmlWithHotspots(snapshot(), 2_000L, ZoneOffset.UTC,
                false, true, "Context", null, List.of(), "", List.of(), "", List.of(), "",
                List.of("<script>alert(1)</script>"), "Hotspots");
        assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains("<script>"));

        String json = ExportRenderers.toJsonWithHotspots(snapshot(), 2_000L, false, true, null,
                List.of(), "", List.of(), "", List.of(), "", List.of("a\"b"), "Hotspots");
        assertTrue(json.contains("a\\\"b"));
    }
}
