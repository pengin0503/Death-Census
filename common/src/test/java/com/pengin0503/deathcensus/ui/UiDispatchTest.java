package com.pengin0503.deathcensus.ui;

import com.pengin0503.deathcensus.DeathEntry;
import com.pengin0503.deathcensus.PlayerDeaths;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiDispatchTest {
    private static Object open(UiResult result) {
        return assertInstanceOf(UiResult.Open.class, result).view();
    }

    private static FakeUiContext home(FakeUiContext ctx) {
        UiActions.handle(new UiRequest(ctx.viewer, "home"), ctx);
        return ctx;
    }

    @Test
    void homeUsesAnonymousServerTotalAndFilteredLastDeath() {
        List<PlayerDeaths> players = List.of(
                TestData.player(TestData.ALICE, "Alice", false,
                        TestData.death(TestData.ALICE, "Alice", 2_000L, "lava")),
                TestData.player(TestData.BOB, "Bob", true,
                        TestData.death(TestData.BOB, "Bob", 8_000L, "drown"),
                        TestData.death(TestData.BOB, "Bob", 9_000L, "fall")),
                TestData.player(TestData.CAROL, "Carol", false,
                        TestData.death(TestData.CAROL, "Carol", 1_000L, "mob")));
        HomeView view = assertInstanceOf(HomeView.class, open(UiActions.handle(
                new UiRequest(TestData.ALICE, "deathcensus:ui/home"), new FakeUiContext(TestData.ALICE, players))));
        assertEquals(1, view.totalDeaths());
        assertEquals(4, view.serverTotalDeaths());
        assertEquals(42, view.currentSurvivalTicks());
        assertFalse(view.admin());
        assertTrue(view.lastDeathLine().contains("lava"));
        assertFalse(view.lastDeathLine().contains("Bob"));

        UUID stranger = UUID.fromString("00000000-0000-0000-0000-999999999999");
        FakeUiContext emptyViewer = new FakeUiContext(stranger, List.of(TestData.player(
                TestData.BOB, "Bob", true, TestData.death(TestData.BOB, "Bob", 9_000L, "fall"))));
        HomeView noVisibleRecord = assertInstanceOf(HomeView.class, open(UiActions.handle(
                new UiRequest(stranger, "home"), emptyViewer)));
        assertEquals(UiText.RECORD_UNAVAILABLE_KEY, noVisibleRecord.lastDeathLine());
        assertEquals(1, noVisibleRecord.serverTotalDeaths());
    }

    @Test
    void historyUsesPaginationOfAndSessionFilter() {
        List<DeathEntry> deaths = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            deaths.add(TestData.death(TestData.ALICE, "Alice", 1_000L + i,
                    i % 2 == 0 ? "fall" : "lava"));
        }
        List<PlayerDeaths> players = List.of(
                TestData.player(TestData.ALICE, "Alice", false, deaths.toArray(DeathEntry[]::new)),
                TestData.player(TestData.BOB, "Bob", true,
                        TestData.death(TestData.BOB, "Bob", 99_000L, "drown")));
        FakeUiContext ctx = home(new FakeUiContext(TestData.ALICE, players));

        HistoryView first = assertInstanceOf(HistoryView.class, open(UiActions.handle(
                new UiRequest(TestData.ALICE, "history", ctx.token(), 1, "", -1L, Map.of()), ctx)));
        assertEquals(25, first.total());
        assertEquals(3, first.pageCount());
        assertEquals(1, first.page());
        assertEquals(10, first.rows().size());
        assertTrue(first.rows().get(0).displayLine().startsWith("#25"));

        HistoryView clamped = assertInstanceOf(HistoryView.class, open(UiActions.handle(
                new UiRequest(TestData.ALICE, "history_page", ctx.token(), 99, "", -1L, Map.of()), ctx)));
        assertEquals(3, clamped.page());
        assertEquals(5, clamped.rows().size());
        assertFalse(clamped.rows().stream().anyMatch(row -> row.displayLine().contains("Bob")));

        HistoryView searched = assertInstanceOf(HistoryView.class, open(UiActions.handle(
                new UiRequest(TestData.ALICE, "history_search", ctx.token(), 1, "", -1L,
                        Map.of("in_query", "lava")), ctx)));
        assertEquals(12, searched.total());
        assertEquals("lava", searched.filter().query());
        assertTrue(searched.rows().stream().allMatch(row -> row.displayLine().contains("lava")));
    }

    @Test
    void detailVisibilityAndMutationFlagsComeFromViewer() {
        DeathEntry bobDeath = TestData.death(TestData.BOB, "Bob", 4_000L, "fall");
        List<PlayerDeaths> players = List.of(
                TestData.player(TestData.BOB, "Bob", true, bobDeath));
        UiRequest detail = new UiRequest(TestData.ALICE, "detail", "", 1, TestData.BOB.toString(), 4_000L, Map.of());

        FakeUiContext other = home(new FakeUiContext(TestData.ALICE, players));
        NoticeView hidden = assertInstanceOf(NoticeView.class, open(UiActions.handle(detail, other)));
        assertEquals(UiText.RECORD_UNAVAILABLE_KEY, hidden.message().key());
        assertTrue(other.calls.isEmpty());

        FakeUiContext self = home(new FakeUiContext(TestData.BOB, players));
        DeathDetailView own = assertInstanceOf(DeathDetailView.class, open(UiActions.handle(
                detail.withTarget(TestData.BOB, 4_000L), self)));
        assertTrue(own.canEditExcuse());
        assertFalse(own.canDelete());
        assertEquals("fall", own.causeCategory());

        FakeUiContext admin = home(new FakeUiContext(TestData.ALICE, players));
        admin.admin = true;
        DeathDetailView asAdmin = assertInstanceOf(DeathDetailView.class, open(UiActions.handle(detail, admin)));
        assertFalse(asAdmin.canEditExcuse());
        assertTrue(asAdmin.canDelete());
    }

    @Test
    void rankingsExcludeOptedOutNamesButFooterKeepsAnonymousCounts() {
        List<PlayerDeaths> players = List.of(
                TestData.player(TestData.ALICE, "Alice", false,
                        TestData.death(TestData.ALICE, "Alice", 1_000L, "fall"),
                        TestData.death(TestData.ALICE, "Alice", 2_000L, "lava")),
                TestData.player(TestData.BOB, "Bob", true,
                        TestData.death(TestData.BOB, "Bob", 3_000L, "lava"),
                        TestData.death(TestData.BOB, "Bob", 4_000L, "drown")));
        FakeUiContext ctx = home(new FakeUiContext(TestData.ALICE, players));
        UiRequest request = new UiRequest(TestData.ALICE, "rankings", ctx.token(), 99, "", -1L,
                Map.of("in_type", "deaths", "in_period", "all"));
        RankingView view = assertInstanceOf(RankingView.class, open(UiActions.handle(request, ctx)));
        assertEquals("deaths", view.type());
        assertEquals(1, view.page());
        assertEquals(1, view.pageCount());
        assertEquals(1, view.rows().size());
        assertTrue(view.rows().get(0).contains("Alice"));
        assertFalse(view.rows().stream().anyMatch(row -> row.contains("Bob")));
        assertTrue(view.anonymousTotalsLine().contains("total=4"));
        assertTrue(view.anonymousTotalsLine().contains("lava=2"));
        assertFalse(view.anonymousTotalsLine().contains("Bob"));
    }

    @Test
    void privacySaveAppliesAndConsumesSession() {
        FakeUiContext ctx = home(new FakeUiContext(TestData.ALICE, List.of()));
        String token = ctx.token();
        UiResult.SideEffect effect = assertInstanceOf(UiResult.SideEffect.class, UiActions.handle(
                new UiRequest(TestData.ALICE, "privacy_save", token, 1, "", -1L,
                        Map.of("in_optout", "true")), ctx));
        assertTrue(effect.applied());
        assertTrue(ctx.calls.contains("optout:true"));
        assertTrue(ctx.token().isEmpty());
        assertInstanceOf(HomeView.class, effect.followUp().view());
    }

    @Test
    void deleteIsAdminOnlyAndBackupFailureHasNoMutation() {
        DeathEntry death = TestData.death(TestData.BOB, "Bob", 4_000L, "fall");
        List<PlayerDeaths> players = List.of(TestData.player(TestData.BOB, "Bob", true, death));
        UiRequest ask = new UiRequest(TestData.CAROL, "delete_ask", "", 1,
                TestData.BOB.toString(), 4_000L, Map.of());
        FakeUiContext nonAdmin = home(new FakeUiContext(TestData.CAROL, players));
        assertEquals("not_admin", assertInstanceOf(UiResult.NoChange.class,
                UiActions.handle(ask, nonAdmin)).reason());
        assertTrue(nonAdmin.calls.isEmpty());

        FakeUiContext admin = home(new FakeUiContext(TestData.ALICE, players));
        admin.admin = true;
        UiRequest adminAsk = new UiRequest(TestData.ALICE, "delete_ask", admin.token(), 1,
                TestData.BOB.toString(), 4_000L, Map.of());
        ConfirmationView confirmation = assertInstanceOf(ConfirmationView.class,
                open(UiActions.handle(adminAsk, admin)));
        assertEquals("delete_confirm", confirmation.yes().action());
        UiRequest confirm = new UiRequest(TestData.ALICE, "delete_confirm", admin.token(), 1,
                TestData.BOB.toString(), 4_000L, Map.of());
        UiResult.SideEffect deleted = assertInstanceOf(UiResult.SideEffect.class, UiActions.handle(confirm, admin));
        assertTrue(deleted.applied());
        assertTrue(admin.calls.contains("delete:" + TestData.BOB + ":4000"));
        assertInstanceOf(HistoryView.class, deleted.followUp().view());

        FakeUiContext failed = home(new FakeUiContext(TestData.ALICE, players));
        failed.admin = true;
        failed.deleteResult = false;
        UiRequest failedConfirm = new UiRequest(TestData.ALICE, "delete_confirm", failed.token(), 1,
                TestData.BOB.toString(), 4_000L, Map.of());
        NoticeView notice = assertInstanceOf(NoticeView.class, open(UiActions.handle(failedConfirm, failed)));
        assertEquals("command.deathcensus.delete.backup_failed", notice.message().key());
    }

    @Test
    void excuseRequiresSelfAndRespectRequiresVisibility() {
        DeathEntry bobDeath = TestData.death(TestData.BOB, "Bob", 4_000L, "fall");
        List<PlayerDeaths> players = List.of(TestData.player(TestData.BOB, "Bob", true, bobDeath));
        UiRequest openExcuse = new UiRequest(TestData.ALICE, "excuse_open", "", 1,
                TestData.BOB.toString(), 4_000L, Map.of());
        FakeUiContext other = home(new FakeUiContext(TestData.ALICE, players));
        assertInstanceOf(NoticeView.class, open(UiActions.handle(openExcuse, other)));

        FakeUiContext self = home(new FakeUiContext(TestData.BOB, players));
        ConfirmationView confirmation = assertInstanceOf(ConfirmationView.class,
                open(UiActions.handle(new UiRequest(TestData.BOB, "excuse_open", self.token(), 1,
                        TestData.BOB.toString(), 4_000L, Map.of()), self)));
        assertEquals("excuse_save", confirmation.yes().action());
        UiResult.SideEffect saved = assertInstanceOf(UiResult.SideEffect.class, UiActions.handle(
                new UiRequest(TestData.BOB, "excuse_save", self.token(), 1,
                        TestData.BOB.toString(), 4_000L, Map.of("in_excuse", "sorry")), self));
        assertTrue(saved.applied());
        assertTrue(self.calls.stream().anyMatch(call -> call.startsWith("excuse:") && call.endsWith(":sorry")));

        FakeUiContext viewer = home(new FakeUiContext(TestData.ALICE, players));
        UiRequest respect = new UiRequest(TestData.ALICE, "respect", viewer.token(), 1,
                TestData.BOB.toString(), 4_000L, Map.of());
        assertInstanceOf(NoticeView.class, open(UiActions.handle(respect, viewer)));
        assertTrue(viewer.calls.isEmpty());
    }
}
