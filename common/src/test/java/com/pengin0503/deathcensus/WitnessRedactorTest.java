package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN 1.4.0 §1/§8/§15 Q3: privacy composition never rewrites the stored record. */
class WitnessRedactorTest {
    private static final long T = 1_700_000_000_000L;

    private static DeathEntry entry(List<WitnessRef> witnesses) {
        return new DeathEntry("victim", "Victim", T, 1L, 100, 100, "minecraft:overworld",
                1.0, 2.0, 3.0, "fall", "minecraft:fall", "Victim fell",
                "", "", "survival", 1, "", "", 0, witnesses);
    }

    private static final List<WitnessRef> CAPTURED = List.of(
            new WitnessRef("alice", "Alice at death"),
            new WitnessRef("bob", "Bob at death"),
            new WitnessRef("carol", "Carol at death"));

    private static List<PlayerDeaths> snapshot() {
        return List.of(
                new PlayerDeaths("alice", "Alice now", false, List.of()),
                new PlayerDeaths("bob", "Bob now", true, false, false, false, List.of()),
                new PlayerDeaths("carol", "", false, List.of()));
    }

    @Test
    void namesAndPrivacyAreComposedFromTheSnapshot() {
        assertEquals("Alice now", WitnessRedactor.namesById(snapshot()).get("alice"));
        assertTrue(WitnessRedactor.privacyById(snapshot()).get("bob").hidesPublicIdentity());
        assertEquals(Map.of(), WitnessRedactor.namesById(null));
        assertEquals(Map.of(), WitnessRedactor.privacyById(null));
    }

    @Test
    void publicViewUsesCurrentNamesAndHidesOptedOutWitnesses() {
        DeathEntry death = entry(CAPTURED);
        assertEquals(List.of("Alice now", WitnessRedactor.HIDDEN_NAME_MARKER, "Carol at death"),
                WitnessRedactor.displayNames(death, snapshot(), false));
        assertEquals("Alice now, Anonymous, Carol at death",
                WitnessRedactor.joinNames(WitnessRedactor.publicNames(death, snapshot())));

        // Self/admin keep the captured truth, including the opted-out witness name.
        assertEquals(List.of("Alice now", "Bob now", "Carol at death"),
                WitnessRedactor.displayNames(death, snapshot(), true));
        List<WitnessRef> refs = WitnessRedactor.viewRefs(death,
                WitnessRedactor.namesById(snapshot()), WitnessRedactor.privacyById(snapshot()), false);
        assertEquals(List.of("alice", "bob", "carol"), refs.stream().map(WitnessRef::uuid).toList());
        assertEquals(WitnessRedactor.HIDDEN_NAME_MARKER, refs.get(1).nameAtDeath());

        // The immutable stored record is untouched by every render.
        assertEquals(CAPTURED, death.witnesses());
        assertFalse(death.witnesses().stream().anyMatch(w -> w.nameAtDeath().equals("Anonymous")));
    }

    @Test
    void emptyAndNullInputsRenderAsEmptyWithoutAllocation() {
        assertTrue(WitnessRedactor.displayNames(null, snapshot(), false).isEmpty());
        assertTrue(WitnessRedactor.displayNames(entry(List.of()), snapshot(), false).isEmpty());
        assertTrue(WitnessRedactor.viewRefs(null, Map.of(), Map.of(), false).isEmpty());
        assertEquals("", WitnessRedactor.joinNames(List.of()));
        assertEquals("", WitnessRedactor.joinNames(null));
        assertEquals("A, B", WitnessRedactor.joinNames(List.of("A", "B")));
    }

    @Test
    void fullOptOutIsTheOnlyIdentityGate() {
        PlayerPrivacy discordOnly = new PlayerPrivacy(false, true, true, true);
        Map<String, PlayerPrivacy> privacy = Map.of(
                "alice", discordOnly,
                "bob", new PlayerPrivacy(true, false, false, false));
        List<String> names = WitnessRedactor.displayNames(entry(CAPTURED),
                WitnessRedactor.namesById(snapshot()), privacy, false);
        assertEquals("Alice now", names.get(0)); // granular flags alone never hide a witness name
        assertTrue(names.contains(WitnessRedactor.HIDDEN_NAME_MARKER)); // full opt-out does
    }
}
