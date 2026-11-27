package com.pengin0503.deathcensus.ui;

import com.pengin0503.deathcensus.DeathContext;
import com.pengin0503.deathcensus.DeathEntry;
import com.pengin0503.deathcensus.DeathRedactor;
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
    void detailViewCarriesContextWithBiomeRedactedForPublicViewers() {
        DeathContext context = new DeathContext("minecraft:sword", List.of("minecraft:helmet"),
                List.of("minecraft:speed:1"), "minecraft:plains", "clear");
        DeathEntry death = new DeathEntry(TestData.BOB.toString(), "Bob", 4_000L, 1L, 100, 100,
                "minecraft:overworld", 1, 2, 3, "fall", "minecraft:fall", "Bob fell",
                "", "", "survival", 1, "", "", 0, List.of(), context);
        List<PlayerDeaths> players = List.of(new PlayerDeaths(TestData.BOB.toString(), "Bob",
                false, true, false, false, List.of(death)));
        UiRequest detail = new UiRequest(TestData.ALICE, "detail", "", 1,
                TestData.BOB.toString(), 4_000L, Map.of());

        FakeUiContext self = home(new FakeUiContext(TestData.BOB, players));
        DeathDetailView own = assertInstanceOf(DeathDetailView.class, open(UiActions.handle(
                detail.withTarget(TestData.BOB, 4_000L), self)));
        assertEquals(context, own.context());

        FakeUiContext other = home(new FakeUiContext(TestData.ALICE, players));
        DeathDetailView publicView = assertInstanceOf(DeathDetailView.class, open(UiActions.handle(detail, other)));
        assertEquals(context.withBiome(DeathRedactor.COORDINATE_MARKER), publicView.context());
        assertEquals("minecraft:plains", death.context().biome());
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
        assertEquals("command.deathcensus.delete.done", deleted.feedback().key());
        assertEquals(3, deleted.feedback().args().length);
        assertEquals("Bob", deleted.feedback().args()[0]);
        assertEquals(1, deleted.feedback().args()[1]); // 1-based index
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

        // Death-line respects carry no session token; visibility is still
        // re-evaluated against live data and the dedupe helper is reused.
        List<PlayerDeaths> publicData = List.of(TestData.player(TestData.BOB, "Bob", false, bobDeath));
        FakeUiContext deathLine = new FakeUiContext(TestData.ALICE, publicData);
        UiResult.SideEffect applied = assertInstanceOf(UiResult.SideEffect.class, UiActions.handle(
                new UiRequest(TestData.ALICE, "respect", "", 1, TestData.BOB.toString(), 4_000L, Map.of()),
                deathLine));
        assertTrue(applied.applied());
        assertTrue(deathLine.calls.contains("respect:" + TestData.ALICE + ":" + TestData.BOB + ":4000"));
        assertTrue(deathLine.currentSession() == null); // no token to consume or issue
    }

    // ------------------------------------------------------------ PR-8b privacy

    private static DeathEntry pvpDeath() {
        return new DeathEntry(TestData.BOB.toString(), "Bob", 1_000L, 1L, 100, 100,
                "minecraft:overworld", 12.0, 64.0, -8.0, "player", "minecraft:player",
                "Bob was slain by Steve", "minecraft:player", "Steve",
                "survival", 5, "gg", "oops", 1);
    }

    @Test
    void privacyViewShowsFourFlagsAndSavesThemIndependently() {
        FakeUiContext ctx = new FakeUiContext(TestData.ALICE, List.of());
        ctx.newSession(HistoryFilter.DEFAULT, UiSession.DEFAULT_RANKING);
        PrivacyView before = assertInstanceOf(PrivacyView.class, open(UiActions.handle(
                new UiRequest(TestData.ALICE, "deathcensus:ui/privacy", ctx.token(),
                        1, "", -1L, Map.of()), ctx)));
        assertFalse(before.optOut());
        assertFalse(before.hideCoordinates());
        assertFalse(before.hidePvpOpponent());
        assertFalse(before.discordOptOut());

        ctx.newSession(HistoryFilter.DEFAULT, UiSession.DEFAULT_RANKING);
        UiResult saved = UiActions.handle(new UiRequest(TestData.ALICE, "privacy_save", ctx.token(),
                1, "", -1L, Map.of("in_optout", "true", "in_hide_coords", "true",
                "in_hide_opponent", "false", "in_discord_optout", "true")), ctx);
        assertInstanceOf(UiResult.SideEffect.class, saved);
        assertTrue(ctx.calls.contains("privacy:" + TestData.ALICE + ":full:true"));
        assertTrue(ctx.calls.contains("privacy:" + TestData.ALICE + ":coords:true"));
        assertTrue(ctx.calls.contains("privacy:" + TestData.ALICE + ":opponent:false"));
        assertTrue(ctx.calls.contains("privacy:" + TestData.ALICE + ":discord:true"));

        ctx.newSession(HistoryFilter.DEFAULT, UiSession.DEFAULT_RANKING);
        PrivacyView after = assertInstanceOf(PrivacyView.class, open(UiActions.handle(
                new UiRequest(TestData.ALICE, "privacy"), ctx)));
        assertTrue(after.optOut());
        assertTrue(after.hideCoordinates());
        assertFalse(after.hidePvpOpponent());
        assertTrue(after.discordOptOut());
        assertEquals("command.deathcensus.ui.privacy.explanation", after.explanation().key());
    }

    @Test
    void otherPlayersPrivacyRequiresThePrivacyOtherDecision() {
        PlayerDeaths bob = new PlayerDeaths(TestData.BOB.toString(), "Bob", false,
                true, true, true, List.of(pvpDeath()));
        FakeUiContext viewer = new FakeUiContext(TestData.ALICE, List.of(bob));
        viewer.newSession(HistoryFilter.DEFAULT, UiSession.DEFAULT_RANKING);
        assertEquals("not_allowed", assertInstanceOf(UiResult.NoChange.class, UiActions.handle(
                new UiRequest(TestData.ALICE, "privacy", viewer.token(), 1,
                        TestData.BOB.toString(), -1L, Map.of()), viewer)).reason());
        assertEquals("not_allowed", assertInstanceOf(UiResult.NoChange.class, UiActions.handle(
                new UiRequest(TestData.ALICE, "privacy_save", viewer.token(), 1,
                        TestData.BOB.toString(), -1L, Map.of("in_hide_coords", "false")), viewer)).reason());
        assertTrue(viewer.calls.isEmpty());

        viewer.privacyOtherAllowed = true;
        viewer.newSession(HistoryFilter.DEFAULT, UiSession.DEFAULT_RANKING);
        PrivacyView view = assertInstanceOf(PrivacyView.class, open(UiActions.handle(
                new UiRequest(TestData.ALICE, "privacy", viewer.token(), 1,
                        TestData.BOB.toString(), -1L, Map.of()), viewer)));
        assertTrue(view.hideCoordinates());
        assertTrue(view.hidePvpOpponent());
        assertTrue(view.discordOptOut());
        viewer.newSession(HistoryFilter.DEFAULT, UiSession.DEFAULT_RANKING);
        UiActions.handle(new UiRequest(TestData.ALICE, "privacy_save", viewer.token(), 1,
                TestData.BOB.toString(), -1L, Map.of("in_hide_coords", "false")), viewer);
        assertTrue(viewer.calls.contains("privacy:" + TestData.BOB + ":coords:false"));
    }

    @Test
    void detailAndHistoryRedactForStrangersButNotOwnerOrAdmin() {
        PlayerDeaths bob = new PlayerDeaths(TestData.BOB.toString(), "Bob", false,
                true, true, false, List.of(pvpDeath()));

        FakeUiContext stranger = new FakeUiContext(TestData.ALICE, List.of(bob));
        stranger.newSession(HistoryFilter.DEFAULT, UiSession.DEFAULT_RANKING);
        HistoryView rows = assertInstanceOf(HistoryView.class, open(UiActions.handle(
                new UiRequest(TestData.ALICE, "deathcensus:ui/history", stranger.token(),
                        1, "", -1L, Map.of()), stranger)));
        assertEquals(1, rows.rows().size());
        assertTrue(rows.rows().get(0).displayLine().contains("~, ~, ~"));
        assertFalse(rows.rows().get(0).displayLine().contains("Steve"));
        assertFalse(rows.rows().get(0).displayLine().contains("12, 64, -8"));

        stranger.newSession(HistoryFilter.DEFAULT, UiSession.DEFAULT_RANKING);
        DeathDetailView redacted = assertInstanceOf(DeathDetailView.class, open(UiActions.handle(
                new UiRequest(TestData.ALICE, "deathcensus:ui/detail", stranger.token(), 1,
                        TestData.BOB.toString(), 1_000L, Map.of()), stranger)));
        assertTrue(redacted.coordsHidden());
        assertEquals("", redacted.killerName());
        assertFalse(redacted.deathMessage().contains("Steve"));
        assertEquals("player", redacted.causeCategory());

        for (FakeUiContext privileged : List.of(
                new FakeUiContext(TestData.BOB, List.of(bob)),
                new FakeUiContext(TestData.ALICE, List.of(bob)))) {
            if (privileged.viewer.equals(TestData.ALICE)) {
                privileged.admin = true;
            }
            privileged.newSession(HistoryFilter.DEFAULT, UiSession.DEFAULT_RANKING);
            DeathDetailView full = assertInstanceOf(DeathDetailView.class, open(UiActions.handle(
                    new UiRequest(privileged.viewer, "detail", privileged.token(), 1,
                            TestData.BOB.toString(), 1_000L, Map.of()), privileged)));
            assertFalse(full.coordsHidden());
            assertEquals(12, full.x());
            assertEquals("Steve", full.killerName());
            assertTrue(full.deathMessage().contains("Steve"));
        }
    }
}
