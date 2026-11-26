package com.pengin0503.deathcensus.ui;

import com.pengin0503.deathcensus.DeathEntry;
import com.pengin0503.deathcensus.PlayerDeaths;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiPrivacyTest {
    private static List<PlayerDeaths> players() {
        return List.of(
                TestData.player(TestData.ALICE, "Alice", false,
                        TestData.death(TestData.ALICE, "Alice", 1_000L, "fall"),
                        TestData.death(TestData.ALICE, "Alice", 2_000L, "lava")),
                TestData.player(TestData.BOB, "Bob", true,
                        TestData.death(TestData.BOB, "Bob", 3_000L, "drown"),
                        TestData.death(TestData.BOB, "Bob", 4_000L, "fall"),
                        TestData.death(TestData.BOB, "Bob", 5_000L, "void")),
                TestData.player(TestData.CAROL, "Carol", false,
                        TestData.death(TestData.CAROL, "Carol", 500L, "mob"))
        );
    }

    @Test
    void canViewDetailIsSelfOrAdminOrPublic() {
        assertTrue(UiPrivacy.canViewDetail(TestData.BOB, TestData.BOB.toString(), true, false));
        assertTrue(UiPrivacy.canViewDetail(TestData.ALICE, TestData.BOB.toString(), true, true));
        assertFalse(UiPrivacy.canViewDetail(TestData.ALICE, TestData.BOB.toString(), true, false));
        assertTrue(UiPrivacy.canViewDetail(TestData.ALICE, TestData.CAROL.toString(), false, false));
        assertFalse(UiPrivacy.canViewDetail(null, TestData.CAROL.toString(), false, false));

        FakeUiContext other = new FakeUiContext(TestData.ALICE, players());
        assertFalse(other.canViewDetail(TestData.BOB));
        other.admin = true;
        assertTrue(other.canViewDetail(TestData.BOB));
        FakeUiContext self = new FakeUiContext(TestData.BOB, players());
        assertTrue(self.canViewDetail(TestData.BOB));
    }

    @Test
    void visibilityHidesOptedOutNamesAndRecords() {
        List<PlayerDeaths> data = players();
        List<String> forAlice = UiPrivacy.visiblePlayers(data, TestData.ALICE, false).stream()
                .map(PlayerDeaths::name).toList();
        assertEquals(List.of("Alice", "Carol"), forAlice);
        assertEquals(3, UiPrivacy.visibleDeaths(data, TestData.ALICE, false).size());
        List<String> forBob = UiPrivacy.visiblePlayers(data, TestData.BOB, false).stream()
                .map(PlayerDeaths::name).toList();
        assertEquals(List.of("Alice", "Bob", "Carol"), forBob);
        assertEquals(6, UiPrivacy.visibleDeaths(data, TestData.BOB, false).size());
        assertEquals(3, UiPrivacy.visiblePlayers(data, TestData.ALICE, true).size());

        DeathEntry latest = UiPrivacy.lastVisibleDeath(data, TestData.ALICE, false).orElseThrow();
        assertEquals(2_000L, latest.epochMillis());
        DeathEntry latestForAdmin = UiPrivacy.lastVisibleDeath(data, TestData.ALICE, true).orElseThrow();
        assertEquals(5_000L, latestForAdmin.epochMillis());

        DeathEntry bobDeath = data.get(1).deaths().get(0);
        assertFalse(UiPrivacy.canViewDetail(TestData.ALICE, bobDeath, data, false));
        assertFalse(UiPrivacy.canViewDetail(TestData.CAROL, bobDeath, data, false));
        assertTrue(UiPrivacy.canViewDetail(TestData.BOB, bobDeath, data, false));
        assertTrue(UiPrivacy.canViewDetail(TestData.ALICE, bobDeath, data, true));
    }

    @Test
    void anonymousTotalsIncludeOptedOutDeathsWithoutNames() {
        List<PlayerDeaths> data = players();
        assertEquals(6, UiPrivacy.anonymousServerTotal(data));
        List<Map.Entry<String, Integer>> counts = UiPrivacy.anonymousCauseTotals(data);
        assertEquals(2, counts.stream().filter(e -> e.getKey().equals("fall")).findFirst().orElseThrow().getValue());
        assertEquals(1, counts.stream().filter(e -> e.getKey().equals("drown")).findFirst().orElseThrow().getValue());
        String line = UiPrivacy.anonymousTotalsLine(data);
        assertTrue(line.contains("total=6"));
        assertFalse(line.contains("Bob"));

        List<String> ranked = UiPrivacy.publicDeathRankings(data).stream()
                .map(summary -> summary.name()).toList();
        assertEquals(List.of("Alice", "Carol"), ranked);
    }

    @Test
    void granularFlagsAreSubordinateToFullOptOutVisibility() {
        List<PlayerDeaths> data = List.of(
                new com.pengin0503.deathcensus.PlayerDeaths(
                        TestData.BOB.toString(), "Bob", true, true, true, true,
                        List.of(TestData.death(TestData.BOB, "Bob", 1_000L, "fall"))),
                new com.pengin0503.deathcensus.PlayerDeaths(
                        TestData.CAROL.toString(), "Carol", false, true, true, true,
                        List.of(TestData.death(TestData.CAROL, "Carol", 2_000L, "fall"))));

        // The full opt-out still governs who is visible at all; granular flags
        // never widen or narrow that gate.
        assertEquals(List.of("Carol"), UiPrivacy.visiblePlayers(data, TestData.ALICE, false).stream()
                .map(PlayerDeaths::name).toList());
        assertEquals(2, UiPrivacy.visiblePlayers(data, TestData.ALICE, true).size());
        assertFalse(UiPrivacy.canViewDetail(TestData.ALICE, data.get(0), false));
        assertTrue(UiPrivacy.canViewDetail(TestData.BOB, data.get(0), false));
        // Anonymous totals count every death regardless of any flag.
        assertEquals(2, UiPrivacy.anonymousServerTotal(data));
    }

    @Test
    void mutationPermissionsAreSelfAndAdminOnly() {
        assertTrue(UiPrivacy.canEditExcuse(TestData.ALICE, TestData.ALICE.toString()));
        assertFalse(UiPrivacy.canEditExcuse(TestData.CAROL, TestData.ALICE.toString()));
        assertTrue(UiPrivacy.canDelete(TestData.CAROL, TestData.ALICE.toString(), true));
        assertFalse(UiPrivacy.canDelete(TestData.CAROL, TestData.ALICE.toString(), false));
    }

    @Test
    void privacyAccessIsSelfOrPrivacyOtherOnly() {
        assertTrue(UiPrivacy.canManagePrivacy(TestData.ALICE, TestData.ALICE, false));
        assertFalse(UiPrivacy.canManagePrivacy(TestData.ALICE, TestData.BOB, false));
        assertTrue(UiPrivacy.canManagePrivacy(TestData.ALICE, TestData.BOB, true));
        assertFalse(UiPrivacy.canManagePrivacy(null, TestData.BOB, true));
        assertFalse(UiPrivacy.canManagePrivacy(TestData.ALICE, null, true));

        FakeUiContext viewer = new FakeUiContext(TestData.ALICE, players());
        assertTrue(viewer.canManagePrivacy(TestData.ALICE));
        assertFalse(viewer.canManagePrivacy(TestData.BOB));
        viewer.privacyOtherAllowed = true;
        assertTrue(viewer.canManagePrivacy(TestData.BOB));
        viewer.admin = true;
        assertTrue(viewer.canManagePrivacy(TestData.BOB));
    }

    @Test
    void privacyForReadsAllFourFlagsAndLeavesAggregatesUnredacted() {
        List<PlayerDeaths> data = List.of(new com.pengin0503.deathcensus.PlayerDeaths(
                TestData.BOB.toString(), "Bob", true, true, true, true,
                List.of(TestData.death(TestData.BOB, "Bob", 1_000L, "fall"))));
        var flags = UiPrivacy.privacyFor(data, TestData.BOB.toString());
        assertTrue(flags.optOutPublic());
        assertTrue(flags.hideCoordinates());
        assertTrue(flags.hidePvpOpponent());
        assertTrue(flags.discordOptOut());
        assertEquals(com.pengin0503.deathcensus.PlayerPrivacy.none(),
                UiPrivacy.privacyFor(data, TestData.ALICE.toString()));
        assertEquals("total=1 · fall=1", UiPrivacy.anonymousTotalsLine(data));
    }
}
