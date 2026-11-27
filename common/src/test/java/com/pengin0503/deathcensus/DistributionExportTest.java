package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.4.0 §7: the distribution section is opt-in, escaped and contains only anonymous bins. */
class DistributionExportTest {
    private static final long EPOCH = 1_700_000_000_000L;

    private static DeathEntry death(double y, String cause, long epoch) {
        return new DeathEntry("p", "Player", epoch, 1L, 10, 10, "minecraft:overworld",
                1.0, y, 1.0, cause, "minecraft:" + cause, "died", "", "", "survival", 0, "", "", 0);
    }

    private static List<PlayerDeaths> snapshot() {
        return List.of(
                new PlayerDeaths("secret", "Secret", true, false, false, false,
                        List.of(death(-64.0, "fall", EPOCH))),
                new PlayerDeaths("hidden", "Hidden", false, true, false, false,
                        List.of(death(-128.0, "lava", EPOCH))),
                new PlayerDeaths("public", "Public", false, false, false, false,
                        List.of(death(64.0, "mob", EPOCH))));
    }

    private static List<String> distributionLines() {
        List<String> lines = new java.util.ArrayList<>();
        lines.add("Time of day");
        DistributionAnalysis.timeOfDay(snapshot(), 2, ZoneOffset.UTC)
                .forEach(bin -> lines.add("hour " + bin.hourStart() + " — " + bin.count()));
        lines.add("Y bands");
        DistributionAnalysis.yBands(snapshot(), 16)
                .forEach(band -> lines.add("y " + band.minY() + ".." + band.maxY()
                        + " — " + band.count()));
        return List.copyOf(lines);
    }

    @Test
    void defaultRenderersHaveNoDistributionSection() {
        assertFalse(ExportRenderers.toCsv(snapshot()).contains("distribution_section"));
        assertFalse(ExportRenderers.toJson(snapshot(), 2_000L).contains("\"distributions\""));
        assertFalse(ExportRenderers.toHtml(snapshot(), 2_000L, ZoneOffset.UTC)
                .contains("<h2>Distributions</h2>"));
    }

    @Test
    void distributionSectionsAreRenderedInAllThreeFormats() {
        List<String> lines = distributionLines();
        String csv = ExportRenderers.toCsvWithDistributions(snapshot(), false, true, null,
                List.of(), "", List.of(), "", List.of(), "", List.of(), "", lines, "Distributions");
        assertTrue(csv.contains("distribution_section,distribution_line"));
        assertTrue(csv.contains("Time of day"));
        assertTrue(csv.contains("Y bands"));

        String json = ExportRenderers.toJsonWithDistributions(snapshot(), 2_000L, false, true, null,
                List.of(), "", List.of(), "", List.of(), "", List.of(), "", lines, "Distributions");
        assertTrue(json.contains("\"distributions\""));
        assertTrue(json.contains("\"section\": \"Distributions\""));
        assertTrue(json.contains("Y bands"));

        String html = ExportRenderers.toHtmlWithDistributions(snapshot(), 2_000L, ZoneOffset.UTC,
                false, true, "Context", null, List.of(), "", List.of(), "", List.of(), "",
                List.of(), "", lines, "Distributions");
        assertTrue(html.contains("<h2>Distributions</h2>"));
        assertTrue(html.contains("Time of day"));
    }

    @Test
    void anonymizedExportsKeepAnonymousBinsAndNoHiddenBand() {
        List<String> lines = distributionLines();
        assertTrue(lines.contains("hour 22 — 3"), lines.toString()); // all three deaths, incl. opted-out
        assertTrue(lines.contains("y -64..-49 — 1"), lines.toString()); // opted-out only
        assertFalse(lines.stream().anyMatch(line -> line.contains("-128..-113"))); // hidden coordinates
        assertFalse(lines.stream().anyMatch(line -> line.contains("Secret") || line.contains("Hidden")));

        String json = ExportRenderers.toJsonWithDistributions(snapshot(), 2_000L, true, true, snapshot(),
                List.of(), "", List.of(), "", List.of(), "", List.of(), "", lines, "Distributions");
        assertTrue(json.contains("\"anonymized\": true"));
        assertTrue(json.contains("y -64..-49"));
        assertFalse(json.contains("-128..-113"));
    }

    @Test
    void distributionTextIsEscaped() {
        String html = ExportRenderers.toHtmlWithDistributions(snapshot(), 2_000L, ZoneOffset.UTC,
                false, true, "Context", null, List.of(), "", List.of(), "", List.of(), "",
                List.of(), "", List.of("<script>alert(1)</script>"), "Distributions");
        assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains("<script>"));

        String json = ExportRenderers.toJsonWithDistributions(snapshot(), 2_000L, false, true, null,
                List.of(), "", List.of(), "", List.of(), "", List.of(), "",
                List.of("a\"b"), "Distributions");
        assertTrue(json.contains("a\\\"b"));
    }
}
