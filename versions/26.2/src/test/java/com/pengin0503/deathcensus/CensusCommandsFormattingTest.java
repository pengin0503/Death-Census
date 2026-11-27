package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Review S3: public history lines must not name players who opted out. */
class CensusCommandsFormattingTest {
    private static final String HIDDEN_UUID = "00000000-0000-0000-0000-000000000001";
    private static final String VICTIM_UUID = "00000000-0000-0000-0000-000000000002";

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        assertTrue(start >= 0, "missing marker " + startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue(end >= 0, "missing end marker " + endMarker);
        return source.substring(start, end);
    }

    @Test
    void historyLineScrubsHiddenOpponentFromAnotherPlayersRecord() {
        DeathEntry victim = new DeathEntry(VICTIM_UUID, "Alice", 1_000L, 3L, 900, 800,
                "minecraft:overworld", 1.0, 2.0, 3.0,
                "player", "minecraft:player", "Alice was slain by bob near " + HIDDEN_UUID,
                "minecraft:player", "bob", "survival", 7, "gg bob", "bye " + HIDDEN_UUID, 0);
        PlayerDeaths hidden = new PlayerDeaths(HIDDEN_UUID, "Bob", true, List.of());
        PlayerDeaths alice = new PlayerDeaths(VICTIM_UUID, "Alice", false, List.of(victim));
        HiddenIdentities dictionary = HiddenIdentities.of(List.of(hidden, alice));

        String line = CensusCommands.formatEntryLine(victim, 42, dictionary).toLowerCase();
        assertFalse(line.contains("bob"), "hidden opponent must not appear in public history");
        assertFalse(line.contains(HIDDEN_UUID));
        assertTrue(line.contains(HiddenIdentities.HIDDEN_MARKER.toLowerCase()));
        assertTrue(line.contains("alice was slain by"), "the rest of the line stays readable");

        // Self/admin rendering must see the stored text unchanged.
        String privileged = CensusCommands.formatEntryLine(victim, 42, HiddenIdentities.empty()).toLowerCase();
        assertTrue(privileged.contains("bob"));
        assertEquals("Alice was slain by bob near " + HIDDEN_UUID, victim.deathMessage());
    }

    @Test
    void historyBuildsOneFullSnapshotDictionaryForPublicViewers() throws IOException {
        String sourceDir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(sourceDir, "deathcensus.mainSourceDir system property is not set");
        String commands = Files.readString(
                Path.of(sourceDir, "com", "pengin0503", "deathcensus", "CensusCommands.java"));
        String history = between(commands, "private static int showHistory(CommandContext",
                "    // ------------------------------------------------------------ commands");
        assertTrue(history.contains("isAdmin(ctx)"), "admins must keep the stored names");
        assertTrue(history.contains("self.getUUID().equals(uuid)"), "self views must keep the stored names");
        assertTrue(history.contains("HiddenIdentities.of(CensusData.get(server).snapshot())"),
                "the dictionary must come from the full snapshot, once per render");
        assertEquals(1, history.split("HiddenIdentities\\.of\\(", -1).length - 1,
                "the dictionary must be built only once per history render");
        assertTrue(history.contains("formatEntryLine(p.items().get(i), p.total() - (base + i), hidden)"));
    }
}
