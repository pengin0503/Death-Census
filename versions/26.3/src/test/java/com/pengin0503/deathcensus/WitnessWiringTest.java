package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-tree compile/wiring check for the PR-2 capture seam and command surface. */
class WitnessWiringTest {
    private static String source(String file) throws IOException {
        String dir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(dir, "deathcensus.mainSourceDir system property is not set");
        return Files.readString(Path.of(dir, "com", "pengin0503", "deathcensus", file));
    }

    @Test
    void configDefaultsStayConservativeBeforeLoad() {
        assertTrue(Config.captureWitnesses());
        assertEquals(24, Config.witnessRadiusBlocks());
        assertEquals(8, Config.witnessMaxPerDeath());
        assertFalse(Config.witnessesInDiscord());
    }

    @Test
    void configDeclaresAllFourKeysWithTheSpecifiedClamps() throws IOException {
        String config = source("Config.java");
        assertTrue(config.contains("define(\"captureWitnesses\", true)"), config);
        assertTrue(config.contains("defineInRange(\"witnessRadiusBlocks\", 24, 8, 64)"), config);
        assertTrue(config.contains("defineInRange(\"witnessMaxPerDeath\", 8, 0, 16)"), config);
        assertTrue(config.contains("define(\"witnessesInDiscord\", false)"), config);
    }

    @Test
    void commandTreeExposesEveryWitnessArgumentShape() throws IOException {
        // Source check rather than Brigadier registration: on the 26.x trees
        // Commands.<clinit> needs a bootstrapped registry, which the direct
        // javac harness intentionally does not start.
        String commands = source("CensusCommands.java");
        assertTrue(commands.contains("literal(\"witnesses\")"), commands);
        assertTrue(commands.contains("argument(\"player\", StringArgumentType.word())"), commands);
        assertTrue(commands.contains("argument(\"page\", IntegerArgumentType.integer(1))"), commands);
        assertTrue(commands.contains("for (String period : PERIODS)"), commands);
        assertTrue(commands.contains("witnesses.then(literal(period)"), commands);
        assertTrue(commands.contains("witnessPlayer.then(literal(period)"), commands);
        assertTrue(commands.contains("witnesses(ctx, null, period, 1)"), commands);
        assertTrue(commands.contains("witnesses(ctx, StringArgumentType.getString(ctx, \"player\"), period, 1)"),
                commands);
        assertTrue(commands.contains("command.deathcensus.witnesses.header"), commands);
    }

    @Test
    void deathHandlerUsesOnlyTheBoundedCommonSelectionPath() throws IOException {
        String tracker = source("DeathTracker.java");
        assertTrue(tracker.contains("captureWitnesses(server, player)"), tracker);
        assertTrue(tracker.contains("if (server == null || victim == null || !Config.captureWitnesses())"), tracker);
        assertTrue(tracker.contains("if (cap <= 0)"), tracker);
        assertTrue(tracker.contains("WitnessAnalysis.nearest(candidates, cap)"), tracker);
        assertFalse(tracker.contains("getPlayerList().getPlayers().stream().findAny()"), tracker);
    }

    @Test
    void gateIsSafeWithNoServerOrVictim() {
        assertTrue(DeathTracker.captureWitnesses(null, null).isEmpty());
    }
}
