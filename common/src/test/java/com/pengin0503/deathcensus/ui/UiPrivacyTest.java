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
    void mutationPermissionsAreSelfAndAdminOnly() {
        assertTrue(UiPrivacy.canEditExcuse(TestData.ALICE, TestData.ALICE.toString()));
        assertFalse(UiPrivacy.canEditExcuse(TestData.CAROL, TestData.ALICE.toString()));
        assertTrue(UiPrivacy.canDelete(TestData.CAROL, TestData.ALICE.toString(), true));
        assertFalse(UiPrivacy.canDelete(TestData.CAROL, TestData.ALICE.toString(), false));
    }
}
