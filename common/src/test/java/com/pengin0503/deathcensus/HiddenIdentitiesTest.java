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

        // The stored record and self/admin views keep the truth.
        assertEquals("Alice", victim.killerName());
        assertEquals("Alice", DeathRedactor.apply(victim, PlayerPrivacy.none(), true, dictionary).killerName());
        DeathEntry viaPublicView = DeathRedactor.publicView(List.of(bob), List.of(hidden, bob))
                .get(0).deaths().get(0);
        assertFalse(viaPublicView.deathMessage().toLowerCase().contains("alice"));
        assertEquals(HiddenIdentities.HIDDEN_MARKER, viaPublicView.killerName());
    }

    @Test
    void recordedAliasesAndGranularHiddenOpponentsAreIncluded() {
        DeathEntry rename = death(HIDDEN_UUID, "Alicia", "Alicia fell", "", "", "");
        PlayerDeaths hidden = new PlayerDeaths(HIDDEN_UUID, "Alice", true, List.of(rename));
        // Bob hid the opponent in his own record; "Victor" must not leak via
        // Carol's record either, because the dictionary is snapshot-wide.
        PlayerDeaths bob = new PlayerDeaths(PUBLIC_UUID, "Bob", false, false, true, false,
                List.of(death(PUBLIC_UUID, "Bob", "Bob fell", "Victor", "", "")));
        DeathEntry carolDeath = death(THIRD_UUID, "Carol",
                "Carol saw ALICIA and victor here", "Alicia", "", "");
        PlayerDeaths carol = new PlayerDeaths(THIRD_UUID, "Carol", false, List.of(carolDeath));

        HiddenIdentities dictionary = HiddenIdentities.of(List.of(hidden, bob, carol));
        DeathEntry scrubbed = dictionary.scrub(carolDeath);
        assertFalse(scrubbed.deathMessage().toLowerCase().contains("alicia"));
        assertFalse(scrubbed.deathMessage().toLowerCase().contains("victor"));
        assertEquals(HiddenIdentities.HIDDEN_MARKER, scrubbed.killerName());
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
