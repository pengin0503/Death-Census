package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN 1.4.0 §4: derived nemesis tables, thresholds, ties and privacy composition. */
class NemesisAnalysisTest {
    private static final String VICTIM = "victim-uuid";

    private static DeathEntry death(String killerType, String killerName, boolean playerCause) {
        return death(killerType, killerName, playerCause, 64.0, "was slain by " + killerName);
    }

    private static DeathEntry death(String killerType, String killerName, boolean playerCause,
                                    double y, String message) {
        return new DeathEntry(VICTIM, "Victim", 1_000L, 1L, 300, 200, "minecraft:overworld",
                1.0, y, 2.0, playerCause ? "player" : "mob", killerType, message,
                killerType, killerName, "survival", 4, "", "", 0);
    }

    private static PlayerDeaths victim(DeathEntry... deaths) {
        return new PlayerDeaths(VICTIM, "Victim", false, false, false, false, List.of(deaths));
    }

    private static PlayerDeaths rival(String uuid, String name, boolean optOut, DeathEntry... deaths) {
        return new PlayerDeaths(uuid, name, optOut, false, false, false, List.of(deaths));
    }

    @Test
    void mobSpeciesTableGroupsByKillerTypeAndNeverListsPlayers() {
        PlayerDeaths player = victim(
                death("minecraft:creeper", "", false),
                death("minecraft:creeper", "", false),
                death("minecraft:creeper", "", false),
                death("minecraft:zombie", "", false),
                death("minecraft:zombie", "", false),
                death(NemesisAnalysis.PLAYER_TYPE, "Steve", true),
                death(NemesisAnalysis.PLAYER_TYPE, "Steve", true),
                death(NemesisAnalysis.PLAYER_TYPE, "Steve", true));
        List<NemesisRow> rows = NemesisAnalysis.rank(List.of(player), NemesisAnalysis.Mode.MOB_SPECIES, 1);
        assertEquals(2, rows.size());
        assertEquals("minecraft:creeper", rows.get(0).name());
        assertEquals(3, rows.get(0).count());
        assertEquals("minecraft:zombie", rows.get(1).name());
        assertEquals(2, rows.get(1).count());
        assertTrue(rows.stream().noneMatch(row -> row.name().contains("Steve")));
    }

    @Test
    void namedRivalsPartitionByTypeAndName() {
        PlayerDeaths player = victim(
                death("minecraft:creeper", "Boss", false),
                death("minecraft:creeper", "Boss", false),
                death("minecraft:zombie", "Boss", false),
                death("minecraft:creeper", "", false));
        List<NemesisRow> rows = NemesisAnalysis.rank(List.of(player), NemesisAnalysis.Mode.NAMED_RIVAL, 1);
        assertEquals(2, rows.size());
        assertEquals(2, rows.get(0).count());
        assertEquals("minecraft:creeper", rows.get(0).type());
        assertEquals("Boss", rows.get(0).name());
        assertEquals(1, rows.get(1).count());
        assertEquals("minecraft:zombie", rows.get(1).type());
    }

    @Test
    void pvpRivalsGroupByNameAndTieBreakIsLexicographic() {
        PlayerDeaths player = victim(
                death(NemesisAnalysis.PLAYER_TYPE, "Zoe", true),
                death(NemesisAnalysis.PLAYER_TYPE, "Adam", true),
                death(NemesisAnalysis.PLAYER_TYPE, "Zoe", true),
                death(NemesisAnalysis.PLAYER_TYPE, "Adam", true));
        List<NemesisRow> rows = NemesisAnalysis.rank(List.of(player), NemesisAnalysis.Mode.PVP_RIVAL, 1);
        assertEquals(List.of("Adam", "Zoe"), rows.stream().map(NemesisRow::name).toList());
        assertEquals(List.of(2, 2), rows.stream().map(NemesisRow::count).toList());
    }

    @Test
    void thresholdsAreAppliedPerTable() {
        PlayerDeaths player = victim(
                death("minecraft:creeper", "", false),
                death("minecraft:creeper", "", false),
                death("minecraft:creeper", "Boss", false),
                death(NemesisAnalysis.PLAYER_TYPE, "Steve", true));
        // A named mob is still a mob species, so creeper has three kills here.
        assertEquals(1, NemesisAnalysis.rank(List.of(player), NemesisAnalysis.Mode.MOB_SPECIES, 3).size());
        assertTrue(NemesisAnalysis.rank(List.of(player), NemesisAnalysis.Mode.MOB_SPECIES, 4).isEmpty());
        assertEquals(1, NemesisAnalysis.rank(List.of(player), NemesisAnalysis.Mode.MOB_SPECIES, 2).size());
        assertTrue(NemesisAnalysis.rank(List.of(player), NemesisAnalysis.Mode.NAMED_RIVAL, 2).isEmpty());
        assertTrue(NemesisAnalysis.rank(List.of(player), NemesisAnalysis.Mode.PVP_RIVAL, 2).isEmpty());
        assertEquals(1, NemesisAnalysis.rank(List.of(player), NemesisAnalysis.Mode.PVP_RIVAL, 1).size());
    }

    @Test
    void blankKillerFieldsAreNeverRows() {
        PlayerDeaths player = victim(
                death("", "", false),
                death("minecraft:creeper", "", false),
                death(NemesisAnalysis.PLAYER_TYPE, "", true));
        assertTrue(NemesisAnalysis.rank(List.of(player), NemesisAnalysis.Mode.PVP_RIVAL, 1).isEmpty());
        // A blank custom name does not stop a species from being tallied.
        assertEquals(1, NemesisAnalysis.rank(List.of(player), NemesisAnalysis.Mode.MOB_SPECIES, 1).size());
        assertTrue(NemesisAnalysis.rank(List.of(player), NemesisAnalysis.Mode.NAMED_RIVAL, 1).isEmpty());
    }

    @Test
    void hiddenCoordinatesDoNotExcludeADeathAndNothingIsMutated() {
        PlayerDeaths player = victim(
                death("minecraft:creeper", "", false, Double.NaN, ""),
                death("minecraft:creeper", "", false, Double.NaN, ""));
        PlayerDeaths before = player;
        List<NemesisRow> rows = NemesisAnalysis.rank(List.of(player), NemesisAnalysis.Mode.MOB_SPECIES, 1);
        assertEquals(2, rows.get(0).count());
        assertEquals(before, player);
        assertThrows(UnsupportedOperationException.class, () -> rows.add(rows.get(0)));
    }

    @Test
    void optedOutVictimIsExcludedByTheCallerSuppliedSource() {
        PlayerDeaths optedOut = new PlayerDeaths(VICTIM, "Victim", true, false, false, false,
                List.of(death(NemesisAnalysis.PLAYER_TYPE, "Steve", true)));
        // The common analyzer is pure: the version-side caller filters opted-out
        // victims before passing the source (see CensusCommands.publicNemesisRows).
        assertEquals(1, NemesisAnalysis.rank(List.of(optedOut),
                NemesisAnalysis.Mode.PVP_RIVAL, 1).size());
        assertTrue(NemesisAnalysis.rank(List.of(), NemesisAnalysis.Mode.PVP_RIVAL, 1).isEmpty());
        assertTrue(NemesisAnalysis.rank(null, NemesisAnalysis.Mode.PVP_RIVAL, 1).isEmpty());
    }

    @Test
    void optedOutRivalIsReplacedByAnonymousInPublicView() {
        List<PlayerDeaths> snapshot = List.of(
                victim(death(NemesisAnalysis.PLAYER_TYPE, "Steve", true)),
                rival("steve-uuid", "Steve", true));
        List<NemesisRow> rows = NemesisAnalysis.rank(List.of(snapshot.get(0)),
                NemesisAnalysis.Mode.PVP_RIVAL, 1, snapshot,
                WitnessRedactor.namesById(snapshot), WitnessRedactor.privacyById(snapshot), false);
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).anonymous());
        assertEquals(1, rows.get(0).count());
        List<NemesisRow> truth = NemesisAnalysis.rank(List.of(snapshot.get(0)),
                NemesisAnalysis.Mode.PVP_RIVAL, 1, snapshot,
                WitnessRedactor.namesById(snapshot), WitnessRedactor.privacyById(snapshot), true);
        assertEquals("Steve", truth.get(0).name());
    }

    @Test
    void hidePvpOpponentScrubsAKnownRivalForPublicViewOnly() {
        PlayerDeaths hider = new PlayerDeaths(VICTIM, "Victim", false, false, true, false,
                List.of(death(NemesisAnalysis.PLAYER_TYPE, "Steve", true)));
        List<PlayerDeaths> snapshot = List.of(hider, rival("steve-uuid", "Steve", false));
        List<NemesisRow> publicRows = NemesisAnalysis.rank(List.of(hider),
                NemesisAnalysis.Mode.PVP_RIVAL, 1, snapshot,
                WitnessRedactor.namesById(snapshot), WitnessRedactor.privacyById(snapshot), false);
        assertTrue(publicRows.get(0).anonymous(), publicRows.toString());
        List<NemesisRow> privateRows = NemesisAnalysis.rank(List.of(hider),
                NemesisAnalysis.Mode.PVP_RIVAL, 1, snapshot,
                WitnessRedactor.namesById(snapshot), WitnessRedactor.privacyById(snapshot), true);
        assertEquals("Steve", privateRows.get(0).name());
    }

    @Test
    void longestKnownAliasWinsOverTheCurrentName() {
        DeathEntry asAlias = death(NemesisAnalysis.PLAYER_TYPE, "Alice, Jr.", true);
        List<PlayerDeaths> snapshot = List.of(
                victim(asAlias),
                new PlayerDeaths("alice-uuid", "Alice", true, false, false, false,
                        List.of(new DeathEntry("alice-uuid", "Alice, Jr.", 2_000L, 1L, 10, 10,
                                "minecraft:overworld", 0, 0, 0, "fall", "minecraft:fall", "", "",
                                "", "survival", 1, "", "", 0))));
        List<NemesisRow> rows = NemesisAnalysis.rank(List.of(snapshot.get(0)),
                NemesisAnalysis.Mode.PVP_RIVAL, 1, snapshot,
                WitnessRedactor.namesById(snapshot), WitnessRedactor.privacyById(snapshot), false);
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).anonymous());
    }

    @Test
    void rivalNameIsCappedForDisplayButCountsStayTruthful() {
        String longName = "X".repeat(120);
        PlayerDeaths player = victim(death(NemesisAnalysis.PLAYER_TYPE, longName, true));
        NemesisRow row = NemesisAnalysis.rank(List.of(player), NemesisAnalysis.Mode.PVP_RIVAL, 1).get(0);
        assertEquals(NemesisRow.MAX_NAME_LENGTH, row.name().length());
        assertEquals(1, row.count());
        assertEquals(longName, row.key());
    }

    @Test
    void modeTokensAreStable() {
        assertEquals(NemesisAnalysis.Mode.MOB_SPECIES, NemesisAnalysis.Mode.fromToken("mob"));
        assertEquals(NemesisAnalysis.Mode.NAMED_RIVAL, NemesisAnalysis.Mode.fromToken("named"));
        assertEquals(NemesisAnalysis.Mode.PVP_RIVAL, NemesisAnalysis.Mode.fromToken("pvp"));
        assertEquals(NemesisAnalysis.Mode.PVP_RIVAL, NemesisAnalysis.Mode.fromToken("PVP_RIVAL"));
        assertEquals(null, NemesisAnalysis.Mode.fromToken("unknown"));
        assertFalse(NemesisAnalysis.rank(null, null).iterator().hasNext());
    }

    @Test
    void publicOverloadWithoutFlagMapUsesTheFullSnapshot() {
        List<PlayerDeaths> snapshot = List.of(
                victim(death(NemesisAnalysis.PLAYER_TYPE, "Steve", true)),
                rival("steve-uuid", "Steve", true));
        List<NemesisRow> rows = NemesisAnalysis.rank(List.of(snapshot.get(0)),
                NemesisAnalysis.Mode.PVP_RIVAL, 1, snapshot, Map.of(), Map.of());
        assertTrue(rows.get(0).anonymous());
    }
}
