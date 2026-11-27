package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.4.0 §8: the loop section is opt-in, escaped and uses only public loop rows. */
class LoopExportTest {
    private static final long EPOCH = 1_700_000_000_000L;

    private static DeathEntry death(String uuid, double x, long epoch) {
        return new DeathEntry(uuid, "Player-" + uuid, epoch, 1L, 10, 10, "minecraft:overworld",
                x, 64.0, 1.0, "fall", "minecraft:fall", "died", "", "", "survival", 0, "", "", 0);
    }

    private static List<PlayerDeaths> snapshot() {
        return List.of(
                new PlayerDeaths("p", "Public", false, false, false, false,
                        List.of(death("p", 1.0, EPOCH), death("p", 2.0, EPOCH + 60_000L),
                                death("p", 3.0, EPOCH + 120_000L))),
                new PlayerDeaths("o", "OptedOut", true, false, false, false,
                        List.of(death("o", 1.0, EPOCH), death("o", 2.0, EPOCH + 60_000L),
                                death("o", 3.0, EPOCH + 120_000L))),
                new PlayerDeaths("h", "Hidden", false, true, false, false,
                        List.of(death("h", 1.0, EPOCH), death("h", 2.0, EPOCH + 60_000L),
                                death("h", 3.0, EPOCH + 120_000L))));
    }

    private static List<String> publicLoopLines() {
        List<String> lines = new ArrayList<>();
        for (DeathLoop loop : DeathLoopDetector.findLoops(snapshot(), 48, 600_000L, 3)) {
            lines.add(loop.name() + ": " + loop.count() + " deaths in " + loop.dimension()
                    + " near " + loop.positionLabel() + " · top: " + loop.topCause());
        }
        return lines;
    }

    @Test
    void defaultRenderersHaveNoLoopSection() {
        assertFalse(ExportRenderers.toCsv(snapshot()).contains("loop_section"));
        assertFalse(ExportRenderers.toJson(snapshot(), 2_000L).contains("\"loops\""));
        assertFalse(ExportRenderers.toHtml(snapshot(), 2_000L, ZoneOffset.UTC)
                .contains("<h2>Death loops</h2>"));
    }

    @Test
    void loopSectionsAreRenderedInAllThreeFormats() {
        List<String> lines = publicLoopLines();
        assertTrue(lines.size() == 1, lines.toString());
        assertTrue(lines.get(0).startsWith("Public:"), lines.toString());

        String csv = ExportRenderers.toCsvWithLoops(snapshot(), false, true, null,
                List.of(), "", List.of(), "", List.of(), "", List.of(), "", List.of(), "",
                lines, "Death loops");
        assertTrue(csv.contains("loop_section,loop_line"));
        assertTrue(csv.contains("Public:"));

        String json = ExportRenderers.toJsonWithLoops(snapshot(), 2_000L, false, true, null,
                List.of(), "", List.of(), "", List.of(), "", List.of(), "", List.of(), "",
                lines, "Death loops");
        assertTrue(json.contains("\"loops\""));
        assertTrue(json.contains("\"section\": \"Death loops\""));
        assertTrue(json.contains("Public:"));

        String html = ExportRenderers.toHtmlWithLoops(snapshot(), 2_000L, ZoneOffset.UTC,
                false, true, "Context", null, List.of(), "", List.of(), "", List.of(), "",
                List.of(), "", List.of(), "", lines, "Death loops");
        assertTrue(html.contains("<h2>Death loops</h2>"));
        assertTrue(html.contains("Public:"));
    }

    @Test
    void backwardCompatibleOverloadsDoNotAddTheLoopSection() {
        String csv = ExportRenderers.toCsvWithChronicle(snapshot(), false, true, null,
                List.of(), "", List.of(), "", List.of(), "", List.of(), "", List.of(), "");
        assertFalse(csv.contains("loop_section"));
        String json = ExportRenderers.toJsonWithChronicle(snapshot(), 2_000L, false, true, null,
                List.of(), "", List.of(), "", List.of(), "", List.of(), "", List.of(), "");
        assertFalse(json.contains("\"loops\""));
        String html = ExportRenderers.toHtmlWithChronicle(snapshot(), 2_000L, ZoneOffset.UTC,
                false, true, "Context", null, List.of(), "", List.of(), "", List.of(), "",
                List.of(), "", List.of(), "");
        assertFalse(html.contains("<h2>Death loops</h2>"));
    }

    @Test
    void loopTextIsEscapedAndNeverNamesHiddenOrOptedOutPlayers() {
        String html = ExportRenderers.toHtmlWithLoops(snapshot(), 2_000L, ZoneOffset.UTC,
                false, true, "Context", null, List.of(), "", List.of(), "", List.of(), "",
                List.of(), "", List.of(), "", List.of("<script>alert(1)</script>"), "Death loops");
        assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains("<script>"));

        String json = ExportRenderers.toJsonWithLoops(snapshot(), 2_000L, false, true, null,
                List.of(), "", List.of(), "", List.of(), "", List.of(), "", List.of(), "",
                publicLoopLines(), "Death loops");
        // The export's player array still lists every stored name by design;
        // only the loop section must stay public-only.
        int loopStart = json.indexOf("\"loops\"");
        int loopEnd = json.indexOf("\"players\"", loopStart);
        assertTrue(loopStart > 0 && loopEnd > loopStart);
        String loopSection = json.substring(loopStart, loopEnd);
        assertFalse(loopSection.contains("OptedOut"), loopSection);
        assertFalse(loopSection.contains("Hidden"), loopSection);
    }
}
