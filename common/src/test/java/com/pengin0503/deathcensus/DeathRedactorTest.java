package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN 1.3.0 §7 truth table at the entry level. */
class DeathRedactorTest {
    private static DeathEntry entry() {
        return new DeathEntry(
                "u-Alex", "Alex", 1_000L, 3L, 900, 800,
                "minecraft:overworld", 130.5, 64.0, -130.5,
                "player", "minecraft:player",
                "Alex was slain by Steve", "minecraft:player", "Steve",
                "survival", 7, "bad luck", "gg", 2);
    }

    @Test
    void noFlagsSelfAndAdminKeepTheOriginalRecord() {
        DeathEntry original = entry();
        assertSame(original, DeathRedactor.apply(original, PlayerPrivacy.none(), false));
        assertSame(original, DeathRedactor.apply(original,
                new PlayerPrivacy(true, true, true, true), true));
        assertSame(original, DeathRedactor.apply(original,
                new PlayerPrivacy(false, true, true, true), true));
    }

    @Test
    void hideCoordinatesOnlyHidesCoordinates() {
        DeathEntry original = entry();
        DeathEntry redacted = DeathRedactor.apply(original,
                new PlayerPrivacy(false, true, false, false), false);
        assertNotSame(original, redacted);
        assertTrue(DeathRedactor.isCoordinateHidden(redacted));
        assertFalse(DeathRedactor.isCoordinateHidden(original));
        assertEquals("player", redacted.causeCategory());
        assertEquals("Alex was slain by Steve", redacted.deathMessage());
        assertEquals("Steve", redacted.killerName());
        assertEquals("gg", redacted.lastWords());
        assertEquals(original.epochMillis(), redacted.epochMillis());
    }

    @Test
    void hideOpponentOnlyScrubsKillerNameAndDeathMessage() {
        DeathEntry original = entry();
        DeathEntry redacted = DeathRedactor.apply(original,
                new PlayerPrivacy(false, false, true, false), false);
        assertEquals("", redacted.killerName());
        assertEquals("Alex was slain by ", redacted.deathMessage());
        assertEquals("player", redacted.causeCategory());
        assertEquals("minecraft:player", redacted.causeId());
        assertEquals(130.5, redacted.x());
        assertEquals("bad luck", redacted.excuse());
        assertEquals("Alex was slain by Steve", original.deathMessage());
        assertEquals("Steve", original.killerName());
    }

    @Test
    void opponentScrubIsCaseInsensitiveAndUnicodeSafe() {
        // Review M5: the vanilla death message can change capitalization
        // ("STEVE" after a name-format mod), and Unicode names must fold too.
        DeathEntry original = new DeathEntry("u-Alex", "Alex", 1_000L, 3L, 900, 800,
                "minecraft:overworld", 1.0, 2.0, 3.0, "player", "minecraft:player",
                "Alex was slain by STEVE and Steve and Steveville", "minecraft:player", "Steve",
                "survival", 7, "", "", 2);
        DeathEntry redacted = DeathRedactor.apply(original,
                new PlayerPrivacy(false, false, true, false), false);
        assertEquals("Alex was slain by  and  and Steveville", redacted.deathMessage());
        assertEquals("", redacted.killerName());

        DeathEntry unicode = new DeathEntry("u-Alex", "Alex", 1_000L, 3L, 900, 800,
                "minecraft:overworld", 1.0, 2.0, 3.0, "player", "minecraft:player",
                "Alex was slain by éLODIE", "minecraft:player", "Élodie",
                "survival", 7, "", "", 2);
        DeathEntry clean = DeathRedactor.apply(unicode,
                new PlayerPrivacy(false, false, true, false), false);
        assertEquals("Alex was slain by ", clean.deathMessage());
        assertEquals("Alex was slain by STEVE and Steve and Steveville", original.deathMessage());
    }

    @Test
    void combinedFlagsRedactBoth() {
        DeathEntry redacted = DeathRedactor.apply(entry(),
                new PlayerPrivacy(false, true, true, false), false);
        assertTrue(DeathRedactor.isCoordinateHidden(redacted));
        assertEquals("", redacted.killerName());
        assertEquals("Alex was slain by ", redacted.deathMessage());
        assertEquals("survival", redacted.gameMode());
        assertEquals(2, redacted.fCount());
    }

    @Test
    void hideCoordinatesRedactsOnlyTheBiomeOfDeathContext() {
        DeathContext context = new DeathContext("minecraft:sword", List.of("minecraft:helmet"),
                List.of("minecraft:speed:1"), "minecraft:plains", "rain");
        DeathEntry original = new DeathEntry("u-Alex", "Alex", 1_000L, 3L, 900, 800,
                "minecraft:overworld", 130.5, 64.0, -130.5,
                "player", "minecraft:player", "Alex was slain by Steve",
                "minecraft:player", "Steve", "survival", 7, "", "gg", 2,
                List.of(), context);
        PlayerPrivacy privacy = new PlayerPrivacy(false, true, false, false);

        DeathEntry redacted = DeathRedactor.apply(original, privacy, false);
        assertEquals(DeathRedactor.COORDINATE_MARKER, redacted.context().biome());
        assertEquals(context.mainHand(), redacted.context().mainHand());
        assertEquals(context.armor(), redacted.context().armor());
        assertEquals(context.effects(), redacted.context().effects());
        assertEquals(context.weather(), redacted.context().weather());
        assertEquals("minecraft:plains", original.context().biome(), "source record untouched");

        // Self/admin and players without hideCoordinates keep the stored biome.
        assertSame(original, DeathRedactor.apply(original, privacy, true));
        DeathEntry visible = DeathRedactor.apply(original, PlayerPrivacy.none(), false);
        assertEquals("minecraft:plains", visible.context().biome());
        assertEquals(context, DeathRedactor.contextView(original, privacy, true));
        assertEquals(DeathContext.EMPTY, DeathRedactor.contextView(null, null, false));
    }

    @Test
    void nullBlankAndUnusualInputsAreSafe() {
        assertNull(DeathRedactor.apply(null, PlayerPrivacy.none(), false));
        DeathEntry original = entry();
        assertSame(original, DeathRedactor.apply(original, null, false));
        DeathEntry blank = new DeathEntry("u", "N", 1L, 0L, 0, 0, "", 0, 0, 0,
                "", "", "", "", "", "", 0, "", "", 0);
        DeathEntry redacted = DeathRedactor.apply(blank,
                new PlayerPrivacy(false, false, true, false), false);
        assertEquals("", redacted.deathMessage());
        assertEquals("", redacted.killerName());
        assertFalse(DeathRedactor.isCoordinateHidden(null));
        assertTrue(DeathRedactor.publicView((List<PlayerDeaths>) null).isEmpty());
    }

    @Test
    void publicViewPreservesPlayerFlagsAndLeavesInputUntouched() {
        PlayerDeaths player = new PlayerDeaths("u-Alex", "Alex", false, true, true, true,
                List.of(entry()));
        PlayerDeaths view = DeathRedactor.publicView(player);
        assertEquals(player.uuid(), view.uuid());
        assertEquals(player.name(), view.name());
        assertEquals(player.optOutPublic(), view.optOutPublic());
        assertTrue(view.hideCoordinates());
        assertTrue(view.hidePvpOpponent());
        assertTrue(view.discordOptOut());
        assertTrue(DeathRedactor.isCoordinateHidden(view.deaths().get(0)));
        assertEquals("", view.deaths().get(0).killerName());
        assertFalse(DeathRedactor.isCoordinateHidden(player.deaths().get(0)));
        assertEquals("Steve", player.deaths().get(0).killerName());
    }

    @Test
    void fullOptOutDominatesDiscordSuppressionButNotOtherFlags() {
        PlayerPrivacy full = new PlayerPrivacy(true, false, false, false);
        assertTrue(full.hidesPublicIdentity());
        assertTrue(full.suppressesRealtimeDiscord());
        PlayerPrivacy discordOnly = new PlayerPrivacy(false, false, false, true);
        assertFalse(discordOnly.hidesPublicIdentity());
        assertTrue(discordOnly.suppressesRealtimeDiscord());
        assertFalse(PlayerPrivacy.none().suppressesRealtimeDiscord());
        assertFalse(PlayerPrivacy.of(null).hidesPublicIdentity());
    }

    @Test
    void truthTableMatrixForAllFlagCombinations() {
        DeathEntry original = entry();
        for (int mask = 0; mask < 8; mask++) {
            boolean hideCoords = (mask & 1) != 0;
            boolean hideOpponent = (mask & 2) != 0;
            boolean discordOnly = (mask & 4) != 0;
            for (boolean fullOptOut : new boolean[]{false, true}) {
                PlayerPrivacy privacy = new PlayerPrivacy(
                        fullOptOut, hideCoords, hideOpponent, discordOnly);
                DeathEntry publicEntry = DeathRedactor.apply(original, privacy, false);
                assertEquals(hideCoords, DeathRedactor.isCoordinateHidden(publicEntry), "mask=" + mask);
                assertEquals(hideOpponent, publicEntry.killerName().isEmpty(), "mask=" + mask);
                assertEquals(hideOpponent, !publicEntry.deathMessage().contains("Steve"), "mask=" + mask);
                assertEquals("player", publicEntry.causeCategory(), "mask=" + mask);
                assertEquals(fullOptOut, privacy.hidesPublicIdentity(), "mask=" + mask);
                assertEquals(fullOptOut || discordOnly,
                        privacy.suppressesRealtimeDiscord(), "mask=" + mask);
                assertSame(original, DeathRedactor.apply(original, privacy, true), "mask=" + mask);
            }
        }
        assertEquals(original, entry(), "redaction never mutates the input");
    }

    @Test
    void scrubOpponentUsesWordBoundariesAndCaseInsensitiveMatching() {
        assertEquals("Alex was slain by ",
                DeathRedactor.scrubOpponent("Alex was slain by Steve", "steve"));
        assertEquals("Alex was slain by ",
                DeathRedactor.scrubOpponent("Alex was slain by Steve", "Steve"));
        assertEquals("Stevenson beat Alex",
                DeathRedactor.scrubOpponent("Stevenson beat Alex", "Steve"));
        assertEquals("A toucan flew by",
                DeathRedactor.scrubOpponent("A toucan flew by", "can"));
    }

    @Test
    void scrubOpponentIsNullAndBlankSafe() {
        assertEquals("", DeathRedactor.scrubOpponent(null, "Steve"));
        assertEquals("text", DeathRedactor.scrubOpponent("text", ""));
        assertEquals("text", DeathRedactor.scrubOpponent("text", null));
        assertEquals("", DeathRedactor.scrubOpponent("Steve", "Steve"));
    }

    @Test
    void scrubOpponentMatchesUnicodeNames() {
        assertEquals(" (slain)", DeathRedactor.scrubOpponent("Séverin (slain)", "séverin"));
        assertEquals("", DeathRedactor.scrubOpponent("Étoile", "étoile"));
    }
}
