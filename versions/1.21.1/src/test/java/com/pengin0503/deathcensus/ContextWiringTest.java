package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-tree compile/wiring check for PR-3 death-context capture and surfaces. */
class ContextWiringTest {
    private static String source(String file) throws IOException {
        String dir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(dir, "deathcensus.mainSourceDir system property is not set");
        return Files.readString(Path.of(dir, "com", "pengin0503", "deathcensus", file));
    }

    @Test
    void configDefaultsAreSafeBeforeLoad() {
        assertTrue(Config.captureDeathContext());
        assertEquals(8, Config.deathContextMaxEffects());
        assertFalse(Config.deathContextInDiscord());
        assertTrue(Config.deathContextInExports());
    }

    @Test
    void configDeclaresAllFourKeysWithBoundedClamps() throws IOException {
        String config = source("Config.java");
        assertTrue(config.contains("define(\"captureDeathContext\", true)"), config);
        assertTrue(config.contains("defineInRange(\"deathContextMaxEffects\", 8, 0, 16)"), config);
        assertTrue(config.contains("define(\"deathContextInDiscord\", false)"), config);
        assertTrue(config.contains("define(\"deathContextInExports\", true)"), config);
    }

    @Test
    void deathHandlerCapturesContextBehindOneGate() throws IOException {
        String tracker = source("DeathTracker.java");
        assertTrue(tracker.contains("DeathContext context = captureContext(player)"), tracker);
        assertTrue(tracker.contains("witnesses,\n                context);"), tracker);
        assertTrue(tracker.contains("return DeathContextCapture.capture(player);"), tracker);
        assertFalse(tracker.contains("context.isEmpty() ?"), tracker);
    }

    @Test
    void versionHelperReadsOnlyRegistryIdsAndBoundedLevelState() throws IOException {
        String helper = source("DeathContextCapture.java");
        assertTrue(helper.contains("!Config.captureDeathContext()"), helper);
        assertTrue(helper.contains("BuiltInRegistries.ITEM.getKey"), helper);
        assertTrue(helper.contains("EquipmentSlot.HEAD"), helper);
        assertTrue(helper.contains("getActiveEffects()"), helper);
        assertTrue(helper.contains("getBiome(player.blockPosition())"), helper);
        assertTrue(helper.contains("isThundering()"), helper);
        assertTrue(helper.contains("DeathContext.of("), helper);
        assertFalse(helper.contains("getDisplayName"), helper);
        assertFalse(helper.contains("getHoverName"), helper);
        assertFalse(helper.contains("save("), helper);
    }

    @Test
    void nullCaptureAndDisabledConfigNeedNoWork() {
        assertSame(DeathContext.EMPTY, DeathTracker.captureContext(null));
    }

    @Test
    void detailSurfacesUseTheBundledContextKeys() throws IOException {
        String commands = source("CensusCommands.java");
        assertTrue(commands.contains("command.deathcensus.ui.detail.context.empty"), commands);
        assertTrue(commands.contains("contextSuffix(e.context(), language)"), commands);
        String adapter;
        try {
            adapter = source("ui/DialogUi.java");
        } catch (IOException noDialogUi) {
            adapter = source("ui/ChatUi.java");
        }
        assertTrue(adapter.contains("command.deathcensus.ui.detail.context.main_hand"), adapter);
        assertTrue(adapter.contains("view.context()"), adapter);
    }
}
