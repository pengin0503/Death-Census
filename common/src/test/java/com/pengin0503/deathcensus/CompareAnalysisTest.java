package com.pengin0503.deathcensus;

import com.pengin0503.deathcensus.ui.UiActions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure §6 comparison behaviour: metrics, periods, resolution and the privacy matrix. */
class CompareAnalysisTest {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID A_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID B_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID C_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID RIVAL = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final long DAY = 86_400_000L;
    private static final long NOW = 1_700_000_000_000L;

    private static DeathEntry death(UUID uuid, String name, long epoch, int survival,
                                    String category, String killer) {
        return new DeathEntry(uuid.toString(), name, epoch, 1L, survival + 100, survival,
                "minecraft:overworld", 1.0, 64.0, 2.0, category, category + ":id",
                name + " died", "minecraft:player", killer, "survival", 0, "", "", 0);
    }

    private static PlayerDeaths player(UUID uuid, String name, boolean optOut,
                                      boolean hideOpponent, DeathEntry... deaths) {
        return new PlayerDeaths(uuid.toString(), name, optOut, false, hideOpponent, false,
                Arrays.asList(deaths));
    }

    private static CompareResult compare(List<PlayerDeaths> players, String a, String b,
                                         long since, UUID viewer, boolean admin) {
        return CompareAnalysis.compare(players, a, b, since, Map.of(), viewer, admin);
    }

    @Test
    void equalTotalsAndAverageSurvivalAreAligned() {
        List<PlayerDeaths> players = List.of(
                player(A_ID, "Alice", false, false,
                        death(A_ID, "Alice", NOW - 1000L, 300, "fall", ""),
                        death(A_ID, "Alice", NOW - 500L, 100, "fall", "")),
                player(B_ID, "Bob", false, false,
                        death(B_ID, "Bob", NOW - 900L, 300, "fall", ""),
                        death(B_ID, "Bob", NOW - 400L, 100, "fall", "")));
        CompareResult result = compare(players, "Alice", "Bob", 0L, VIEWER, false);
        assertTrue(result.ok());
        assertEquals(2, result.a().summary().deaths());
        assertEquals(2, result.b().summary().deaths());
        assertEquals(200L, result.a().averageSurvivalTicks());
        assertEquals(200L, result.b().averageSurvivalTicks());
        assertEquals(300, result.a().summary().longestSurvivalTicks());
        assertEquals(100, result.a().summary().shortestSurvivalTicks());
        assertEquals("fall", result.a().topCause());
        assertEquals("fall", result.b().topCause());
    }

    @Test
    void differentTotalsAndTopCausesAreNotSwapped() {
        List<PlayerDeaths> players = List.of(
                player(A_ID, "Alice", false, false,
                        death(A_ID, "Alice", NOW - 10L, 1, "fall", ""),
                        death(A_ID, "Alice", NOW - 9L, 1, "fall", ""),
                        death(A_ID, "Alice", NOW - 8L, 1, "lava", "")),
                player(B_ID, "Bob", false, false,
                        death(B_ID, "Bob", NOW - 7L, 1, "drown", ""),
                        death(B_ID, "Bob", NOW - 6L, 1, "drown", "")));
        CompareResult result = compare(players, "Alice", "Bob", 0L, VIEWER, false);
        assertEquals(3, result.a().summary().deaths());
        assertEquals(2, result.b().summary().deaths());
        assertEquals("fall", result.a().topCause());
        assertEquals("drown", result.b().topCause());
    }

    @Test
    void periodFilteringUsesTheSameCutoffForBothSides() {
        List<PlayerDeaths> players = List.of(
                player(A_ID, "Alice", false, false,
                        death(A_ID, "Alice", NOW - 10 * DAY, 10, "fall", ""),
                        death(A_ID, "Alice", NOW - DAY, 20, "lava", "")),
                player(B_ID, "Bob", false, false,
                        death(B_ID, "Bob", NOW - 2 * DAY, 10, "fall", ""),
                        death(B_ID, "Bob", NOW - DAY / 2, 20, "lava", "")));
        CompareResult result = compare(players, "Alice", "Bob", NOW - DAY, VIEWER, false);
        assertEquals(1, result.a().summary().deaths());
        assertEquals(1, result.b().summary().deaths());
        assertEquals("lava", result.a().topCause());
        assertEquals("lava", result.b().topCause());
    }

    @Test
    void unknownPlayerReturnsUnknownFailure() {
        List<PlayerDeaths> players = List.of(player(A_ID, "Alice", false, false,
                death(A_ID, "Alice", NOW, 1, "fall", "")));
        assertEquals(CompareResult.Failure.UNKNOWN_PLAYER,
                compare(players, "Nobody", "Alice", 0L, VIEWER, false).failure());
        assertEquals(CompareResult.Failure.UNKNOWN_PLAYER,
                compare(players, "Alice", "Nobody", 0L, VIEWER, false).failure());
        assertEquals(CompareResult.Failure.UNKNOWN_PLAYER,
                compare(players, " ", "Alice", 0L, VIEWER, false).failure());
    }

    @Test
    void optedOutPlayerReturnsHiddenFailureForPublicViewer() {
        List<PlayerDeaths> players = List.of(
                player(A_ID, "Alice", false, false, death(A_ID, "Alice", NOW, 1, "fall", "")),
                player(B_ID, "Bob", true, false, death(B_ID, "Bob", NOW, 1, "fall", "")));
        CompareResult result = compare(players, "Alice", "Bob", 0L, VIEWER, false);
        assertEquals(CompareResult.Failure.HIDDEN_PLAYER, result.failure());
        assertEquals(CompareResult.Failure.HIDDEN_PLAYER,
                compare(players, "Bob", "Alice", 0L, VIEWER, false).failure());
    }

    @Test
    void oneSidedOptOutNeverNamesEitherSideToPublic() {
        List<PlayerDeaths> players = List.of(
                player(A_ID, "Alice", true, false, death(A_ID, "Alice", NOW, 1, "fall", "")),
                player(B_ID, "Bob", false, false, death(B_ID, "Bob", NOW, 1, "fall", "")));
        CompareResult result = compare(players, "Alice", "Bob", 0L, VIEWER, false);
        assertEquals(CompareResult.Failure.HIDDEN_PLAYER, result.failure());
        assertFalse(result.ok());
        assertEquals("", result.a() == null ? "" : result.a().name());
    }

    @Test
    void selfViewUnlocksOnlyTheViewerOwnHiddenSide() {
        List<PlayerDeaths> players = List.of(
                player(A_ID, "Alice", true, false, death(A_ID, "Alice", NOW, 1, "fall", "")),
                player(B_ID, "Bob", true, false, death(B_ID, "Bob", NOW, 1, "fall", "")));
        assertEquals(CompareResult.Failure.HIDDEN_PLAYER,
                compare(players, "Alice", "Bob", 0L, A_ID, false).failure());
        assertEquals(CompareResult.Failure.HIDDEN_PLAYER,
                compare(players, "Alice", "Bob", 0L, B_ID, false).failure());
    }

    @Test
    void adminCanCompareBothOptedOutPlayers() {
        List<PlayerDeaths> players = List.of(
                player(A_ID, "Alice", true, false, death(A_ID, "Alice", NOW, 1, "fall", "")),
                player(B_ID, "Bob", true, false, death(B_ID, "Bob", NOW, 1, "fall", "")));
        CompareResult result = compare(players, "Alice", "Bob", 0L, ADMIN, true);
        assertTrue(result.ok());
        assertEquals("Alice", result.a().name());
        assertEquals("Bob", result.b().name());
    }

    @Test
    void consoleViewerCanComparePublicPlayers() {
        List<PlayerDeaths> players = List.of(
                player(A_ID, "Alice", false, false, death(A_ID, "Alice", NOW, 1, "fall", "")),
                player(B_ID, "Bob", false, false, death(B_ID, "Bob", NOW, 1, "fall", "")));
        assertTrue(compare(players, "Alice", "Bob", 0L, null, false).ok());
    }

    @Test
    void playerCausedDeathsCountAndTopOpponentAreDerived() {
        List<PlayerDeaths> players = List.of(
                player(A_ID, "Alice", false, false,
                        death(A_ID, "Alice", NOW - 3L, 1, "player", "Rival"),
                        death(A_ID, "Alice", NOW - 2L, 1, "player", "Rival"),
                        death(A_ID, "Alice", NOW - 1L, 1, "player", "Other"),
                        death(A_ID, "Alice", NOW, 1, "fall", "")),
                player(B_ID, "Bob", false, false, death(B_ID, "Bob", NOW, 1, "fall", "")));
        CompareResult result = compare(players, "Alice", "Bob", 0L, VIEWER, false);
        assertEquals(3, result.a().playerCausedDeaths());
        assertEquals(2, result.a().topOpponentCount());
        assertEquals("Rival", result.a().topOpponent());
        assertFalse(result.a().opponentHidden());
        assertEquals(0, result.b().playerCausedDeaths());
        assertEquals("", result.b().topOpponent());
    }

    @Test
    void victimHidePvpOpponentAppliesOnlyToPublicView() {
        List<PlayerDeaths> players = List.of(
                player(A_ID, "Alice", false, true,
                        death(A_ID, "Alice", NOW, 1, "player", "Rival")),
                player(B_ID, "Bob", false, false, death(B_ID, "Bob", NOW, 1, "fall", "")));
        CompareResult publicResult = compare(players, "Alice", "Bob", 0L, VIEWER, false);
        assertTrue(publicResult.a().opponentHidden());
        assertEquals(HiddenIdentities.HIDDEN_MARKER, publicResult.a().topOpponent());
        assertEquals(1, publicResult.a().topOpponentCount());
        CompareResult selfResult = compare(players, "Alice", "Bob", 0L, A_ID, false);
        assertFalse(selfResult.a().opponentHidden());
        assertEquals("Rival", selfResult.a().topOpponent());
        CompareResult adminResult = compare(players, "Alice", "Bob", 0L, ADMIN, true);
        assertEquals("Rival", adminResult.a().topOpponent());
    }

    @Test
    void optedOutOpponentIsHiddenEvenFromVictimSelfView() {
        List<PlayerDeaths> players = List.of(
                player(A_ID, "Alice", false, false,
                        death(A_ID, "Alice", NOW, 1, "player", "Rival")),
                player(B_ID, "Bob", false, false, death(B_ID, "Bob", NOW, 1, "fall", "")),
                player(RIVAL, "Rival", true, false, death(RIVAL, "Rival", NOW, 1, "fall", "")));
        CompareResult selfResult = compare(players, "Alice", "Bob", 0L, A_ID, false);
        assertTrue(selfResult.a().opponentHidden());
        assertEquals(HiddenIdentities.HIDDEN_MARKER, selfResult.a().topOpponent());
        assertEquals("Alice", selfResult.a().name());
        CompareResult adminResult = compare(players, "Alice", "Bob", 0L, ADMIN, true);
        assertEquals("Rival", adminResult.a().topOpponent());
    }

    @Test
    void hiddenIdentityFromAnotherRecordCannotLeakAsOpponent() {
        List<PlayerDeaths> players = List.of(
                player(A_ID, "Alice", false, false,
                        death(A_ID, "Alice", NOW, 1, "player", "Secret")),
                player(B_ID, "Bob", false, false, death(B_ID, "Bob", NOW, 1, "fall", "")),
                player(C_ID, "Secret", true, false,
                        death(C_ID, "Secret", NOW, 1, "fall", "")));
        CompareResult result = compare(players, "Alice", "Bob", 0L, VIEWER, false);
        assertTrue(result.a().opponentHidden());
        assertEquals(HiddenIdentities.HIDDEN_MARKER, result.a().topOpponent());
    }

    @Test
    void privacyMapOverrideCannotClearStoredOptOutButCanHideOpponent() {
        List<PlayerDeaths> players = List.of(
                player(A_ID, "Alice", true, false,
                        death(A_ID, "Alice", NOW, 1, "player", "Rival")),
                player(B_ID, "Bob", false, false, death(B_ID, "Bob", NOW, 1, "fall", "")));
        Map<String, PlayerPrivacy> misleading = Map.of(
                A_ID.toString(), new PlayerPrivacy(false, false, false, false));
        CompareResult result = CompareAnalysis.compare(players, "Alice", "Bob", 0L,
                misleading, VIEWER, false);
        assertEquals(CompareResult.Failure.HIDDEN_PLAYER, result.failure());

        List<PlayerDeaths> publicPlayers = List.of(
                player(A_ID, "Alice", false, false,
                        death(A_ID, "Alice", NOW, 1, "player", "Rival")),
                player(B_ID, "Bob", false, false, death(B_ID, "Bob", NOW, 1, "fall", "")));
        Map<String, PlayerPrivacy> hideOpponent = Map.of(
                A_ID.toString(), new PlayerPrivacy(false, false, true, false));
        CompareResult overridden = CompareAnalysis.compare(publicPlayers, "Alice", "Bob", 0L,
                hideOpponent, VIEWER, false);
        assertTrue(overridden.a().opponentHidden());
    }

    @Test
    void resolutionIsCaseInsensitiveOnRecordedNamesAndFirstMatchWins() {
        DeathEntry oldAlias = death(A_ID, "Alice", NOW, 1, "fall", "");
        List<PlayerDeaths> players = List.of(
                player(C_ID, "Carol", false, false, death(C_ID, "Carol", NOW - 2L, 2, "fall", "")),
                player(A_ID, "Renamed", false, false,
                        new DeathEntry(A_ID.toString(), "Alice", NOW - 1L, 1L, 3, 3,
                                "minecraft:overworld", 0.0, 64.0, 0.0, "fall", "fall:id",
                                "Alice died", "", "", "survival", 0, "", "", 0)),
                player(B_ID, "Bob", false, false, death(B_ID, "Bob", NOW, 1, "fall", "")));
        CompareResult result = compare(players, "alice", "BOB", 0L, VIEWER, false);
        assertTrue(result.ok());
        assertEquals("Renamed", result.a().name());
        assertEquals("Bob", result.b().name());

        List<PlayerDeaths> ambiguous = List.of(
                player(A_ID, "Dup", false, false, oldAlias),
                player(B_ID, "Dup", false, false, death(B_ID, "Dup", NOW, 1, "fall", "")));
        assertEquals(A_ID.toString(),
                compare(ambiguous, "dup", "Dup", 0L, VIEWER, false).a().uuid());
    }

    @Test
    void periodWithNoRecordsStillYieldsZeroRows() {
        List<PlayerDeaths> players = List.of(
                player(A_ID, "Alice", false, false,
                        death(A_ID, "Alice", NOW - 10 * DAY, 1, "fall", "")),
                player(B_ID, "Bob", false, false,
                        death(B_ID, "Bob", NOW - 10 * DAY, 1, "fall", "")));
        CompareResult result = compare(players, "Alice", "Bob", NOW - DAY, VIEWER, false);
        assertTrue(result.ok());
        assertEquals(0, result.a().summary().deaths());
        assertEquals(0, result.b().summary().deaths());
        assertEquals(0L, result.a().averageSurvivalTicks());
        assertEquals("", result.a().topCause());
    }

    @Test
    void resultHasNoWinsOrLossesComponent() {
        for (java.lang.reflect.RecordComponent component : CompareResult.class.getRecordComponents()) {
            assertFalse(component.getName().toLowerCase(java.util.Locale.ROOT).contains("wins"));
            assertFalse(component.getName().toLowerCase(java.util.Locale.ROOT).contains("loss"));
        }
        for (java.lang.reflect.RecordComponent component
                : CompareResult.Row.class.getRecordComponents()) {
            assertFalse(component.getName().toLowerCase(java.util.Locale.ROOT).contains("wins"));
            assertFalse(component.getName().toLowerCase(java.util.Locale.ROOT).contains("loss"));
        }
    }

    @Test
    void analysisDoesNotMutateItsInput() {
        DeathEntry entry = death(A_ID, "Alice", NOW, 1, "player", "Rival");
        List<DeathEntry> originalDeaths = new ArrayList<>(List.of(entry));
        PlayerDeaths alice = new PlayerDeaths(A_ID.toString(), "Alice", false, false, true,
                false, originalDeaths);
        PlayerDeaths bob = player(B_ID, "Bob", false, false, death(B_ID, "Bob", NOW, 1, "fall", ""));
        List<PlayerDeaths> players = List.of(alice, bob);
        CompareResult result = compare(players, "Alice", "Bob", 0L, VIEWER, false);
        assertTrue(result.ok());
        assertEquals(List.of(entry), alice.deaths());
        assertEquals(1, originalDeaths.size());
        assertEquals("Rival", entry.killerName());
        assertNotNull(result.a().summary());
        assertEquals(2, players.size());
    }

    @Test
    void nameLongerThanTheBoundIsRejected() {
        List<PlayerDeaths> players = List.of(
                player(A_ID, "Alice", false, false, death(A_ID, "Alice", NOW, 1, "fall", "")),
                player(B_ID, "Bob", false, false, death(B_ID, "Bob", NOW, 1, "fall", "")));
        String tooLong = "a".repeat(CompareAnalysis.MAX_NAME_LENGTH + 1);
        assertEquals(CompareResult.Failure.UNKNOWN_PLAYER,
                compare(players, tooLong, "Bob", 0L, VIEWER, false).failure());
        assertEquals("compare", UiActions.resolveAction("compare"));
    }
}
