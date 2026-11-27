package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §6 standalone report renderer: pure, escaped and privacy-agnostic. */
class CompareHtmlTest {
    @Test
    void rendersBothColumnsAndTheGeneratedTimestamp() {
        String html = ExportRenderers.compareHtml("Compare", "Alice", "Bob",
                List.of("Deaths: 5", "Top opponent: <hidden>"),
                List.of("Deaths: 2", "Top opponent: anonymous"),
                1_700_000_000_000L, ZoneOffset.UTC);
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("Alice"));
        assertTrue(html.contains("Bob"));
        assertTrue(html.contains("Deaths: 5"));
        assertTrue(html.contains("Deaths: 2"));
        assertTrue(html.contains("&lt;hidden&gt;"));
        assertTrue(html.contains("<th>Bob</th>"));
    }

    @Test
    void escapesUntrustedNamesAndRows() {
        String html = ExportRenderers.compareHtml("<script>x</script>", "<b>A</b>", "B",
                List.of("<img src=x>"), List.of("Row"), 0L, ZoneOffset.UTC);
        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("<b>A</b>"));
        assertFalse(html.contains("<img src=x>"));
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    void toleratesUnequalOrEmptyColumns() {
        String html = ExportRenderers.compareHtml("Compare", "A", "B",
                List.of("one", "two"), List.of("one"), 0L, null);
        assertTrue(html.contains("two"));
        assertTrue(ExportRenderers.compareHtml("Compare", "A", "B", null, null, 0L, null)
                .contains("<tbody>"));
    }
}
