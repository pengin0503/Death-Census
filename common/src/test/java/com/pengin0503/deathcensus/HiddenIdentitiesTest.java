package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Review S3: a snapshot-wide hidden-identity dictionary for public projections. */
class HiddenIdentitiesTest {
    private static final String HIDDEN_UUID = "00000000-0000-0000-0000-000000000001";
    private static final String PUBLIC_UUID = "00000000-0000-0000-0000-000000000002";
    private static final String THIRD_UUID = "00000000-0000-0000-0000-000000000003";

    private static DeathEntry death(String uuid, String name, String message,
                                    String killerName, String excuse, String lastWords) {
        return new DeathEntry(uuid, name, 1_000L, 3L, 900, 800,
                "minecraft:overworld", 1.0, 2.0, 3.0,
                "player", "minecraft:player", message, "minecraft:player", killerName,
                "survival", 7, excuse, lastWords, 2);
    }

    @Test
    void optedOutNamesAndUuidsAreScrubbedFromEveryPersonalField() {
        DeathEntry victim = death(PUBLIC_UUID, "Bob",
                "BOB was slain by alice near " + HIDDEN_UUID,
                "Alice", "ALICE did it", "bye Alice and " + HIDDEN_UUID);
        PlayerDeaths hidden = new PlayerDeaths(HIDDEN_UUID, "Alice", true,
                List.of(death(HIDDEN_UUID, "Alice", "fell", "", "", "")));
        PlayerDeaths bob = new PlayerDeaths(PUBLIC_UUID, "Bob", false, List.of(victim));
        HiddenIdentities dictionary = HiddenIdentities.of(List.of(hidden, bob));

        DeathEntry publicEntry = dictionary.scrub(victim);
        assertFalse(publicEntry.deathMessage().toLowerCase().contains("alice"));
        assertFalse(publicEntry.deathMessage().contains(HIDDEN_UUID));
        assertFalse(publicEntry.excuse().toLowerCase().contains("alice"));
        assertFalse(publicEntry.lastWords().toLowerCase().contains("alice"));
        assertFalse(publicEntry.lastWords().contains(HIDDEN_UUID));
        assertEquals(HiddenIdentities.HIDDEN_MARKER, publicEntry.killerName());
        assertEquals("BOB was slain by " + HiddenIdentities.HIDDEN_MARKER + " near "
                + HiddenIdentities.HIDDEN_MARKER, publicEntry.deathMessage());

        // The stored record is never changed.
        assertEquals("Alice", victim.killerName());
        assertEquals("BOB was slain by alice near " + HIDDEN_UUID, victim.deathMessage());
    }

    @Test
    void recordedAliasesAreIncludedAndOnlyOptedOutPlayersAreHidden() {
        DeathEntry rename = death(HIDDEN_UUID, "Alicia", "Alicia fell", "", "", "");
        PlayerDeaths hidden = new PlayerDeaths(HIDDEN_UUID, "Alice", true, List.of(rename));
        DeathEntry carolDeath = death(THIRD_UUID, "Carol",
                "Carol saw ALICIA and steve here", "Alicia", "", "");
        PlayerDeaths steve = new PlayerDeaths(PUBLIC_UUID, "Steve", false,
                List.of(death(PUBLIC_UUID, "Steve", "Steve fell", "", "", "")));
        PlayerDeaths carol = new PlayerDeaths(THIRD_UUID, "Carol", false, List.of(carolDeath));

        HiddenIdentities dictionary = HiddenIdentities.of(List.of(hidden, steve, carol));
        DeathEntry scrubbed = dictionary.scrub(carolDeath);
        assertFalse(scrubbed.deathMessage().toLowerCase().contains("alicia"));
        assertEquals(HiddenIdentities.HIDDEN_MARKER, scrubbed.killerName());
        // A non-opted-out player is not part of the dictionary.
        assertTrue(scrubbed.deathMessage().contains("steve"));
    }

    @Test
    void emptySnapshotKeepsTextUnchanged() {
        DeathEntry original = death(PUBLIC_UUID, "Bob", "Bob fell", "Steve", "", "");
        assertTrue(HiddenIdentities.empty().isEmpty());
        assertTrue(HiddenIdentities.of(null).isEmpty());
        assertEquals(original.deathMessage(), HiddenIdentities.empty().scrub(original.deathMessage()));
        assertEquals(original, HiddenIdentities.empty().scrub(original));
        assertEquals("", HiddenIdentities.empty().scrub((String) null));
    }
}
