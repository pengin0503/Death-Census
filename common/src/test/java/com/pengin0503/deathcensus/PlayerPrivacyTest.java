package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PR-8b command/UI contract: four flags, bounded tokens and the access matrix. */
class PlayerPrivacyTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void flagTokensAreStableAndAliasesAreAccepted() {
        assertEquals("full", PlayerPrivacy.Flag.FULL.token());
        assertEquals("coords", PlayerPrivacy.Flag.COORDS.token());
        assertEquals("opponent", PlayerPrivacy.Flag.OPPONENT.token());
        assertEquals("discord", PlayerPrivacy.Flag.DISCORD.token());
        assertEquals("in_optout", PlayerPrivacy.Flag.FULL.inputKey());
        assertEquals("in_hide_coords", PlayerPrivacy.Flag.COORDS.inputKey());
        assertEquals("in_hide_opponent", PlayerPrivacy.Flag.OPPONENT.inputKey());
        assertEquals("in_discord_optout", PlayerPrivacy.Flag.DISCORD.inputKey());
        assertEquals(PlayerPrivacy.Flag.OPPONENT, PlayerPrivacy.Flag.parse(" hidePvpOpponent "));
        assertEquals(PlayerPrivacy.Flag.DISCORD, PlayerPrivacy.Flag.parse("discord_optout"));
        assertNull(PlayerPrivacy.Flag.parse("nope"));
        assertNull(PlayerPrivacy.Flag.parse(null));
    }

    @Test
    void everyToggleLeavesTheOtherThreeUntouched() {
        PlayerPrivacy current = new PlayerPrivacy(true, true, false, true);
        assertEquals(new PlayerPrivacy(false, true, false, true),
                PlayerPrivacy.withFlag(current, PlayerPrivacy.Flag.FULL, false));
        assertEquals(new PlayerPrivacy(true, false, false, true),
                PlayerPrivacy.withFlag(current, PlayerPrivacy.Flag.COORDS, false));
        assertEquals(new PlayerPrivacy(true, true, true, true),
                PlayerPrivacy.withFlag(current, PlayerPrivacy.Flag.OPPONENT, true));
        assertEquals(new PlayerPrivacy(true, true, false, false),
                PlayerPrivacy.withFlag(current, PlayerPrivacy.Flag.DISCORD, false));
        assertEquals(PlayerPrivacy.none(), PlayerPrivacy.withFlag(null, null, true));
        // Opting back in never clears the granular flags.
        assertTrue(PlayerPrivacy.withFlag(current, PlayerPrivacy.Flag.FULL, false).hideCoordinates());
        assertTrue(PlayerPrivacy.withFlag(current, PlayerPrivacy.Flag.FULL, false).discordOptOut());
    }

    @Test
    void stateArgumentIsBounded() {
        assertTrue(PlayerPrivacy.parseState("ON"));
        assertTrue(PlayerPrivacy.parseState("true"));
        assertTrue(PlayerPrivacy.parseState("1"));
        assertFalse(PlayerPrivacy.parseState("off"));
        assertFalse(PlayerPrivacy.parseState("disabled"));
        assertNull(PlayerPrivacy.parseState("maybe"));
        assertNull(PlayerPrivacy.parseState(null));
    }

    @Test
    void otherPlayerAccessRequiresThePrivacyOtherDecision() {
        assertTrue(PlayerPrivacy.canAccess(ALICE, ALICE, false));
        assertFalse(PlayerPrivacy.canAccess(ALICE, BOB, false));
        assertTrue(PlayerPrivacy.canAccess(ALICE, BOB, true));
        // Console/admin without a player identity is still allowed when gated.
        assertTrue(PlayerPrivacy.canAccess(null, BOB, true));
        assertFalse(PlayerPrivacy.canAccess(null, BOB, false));
        assertFalse(PlayerPrivacy.canAccess(ALICE, null, true));
    }

    @Test
    void snapshotProjectionPreservesAllFourFlags() {
        PlayerDeaths player = new PlayerDeaths(ALICE.toString(), "Alice", true, true, false, true,
                List.of());
        assertEquals(new PlayerPrivacy(true, true, false, true), PlayerPrivacy.of(player));
        assertEquals(PlayerPrivacy.none(), PlayerPrivacy.of(null));
        assertFalse(PlayerPrivacy.none().hidesPublicIdentity());
        assertTrue(new PlayerPrivacy(false, false, false, true).suppressesRealtimeDiscord());
    }
}
