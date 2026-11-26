package com.pengin0503.deathcensus.ui;

import com.pengin0503.deathcensus.PlayerDeaths;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiSessionTest {
    @Test
    void ttlIsFiveMinutes() {
        UiSession session = new UiSession("token", 1_000L, HistoryFilter.DEFAULT, UiSession.DEFAULT_RANKING, 3L);
        assertEquals(300_000L, UiSession.TTL_MILLIS);
        assertFalse(session.isExpired(1_000L + UiSession.TTL_MILLIS - 1L));
        assertTrue(session.isExpired(1_000L + UiSession.TTL_MILLIS));
        assertTrue(session.isExpired(999L));
    }

    @Test
    void rotationMintsNewTokenAndMutationConsumesIt() {
        UiSessionStore store = new UiSessionStore(() -> 0L);
        UiSession first = store.issue(TestData.ALICE, HistoryFilter.DEFAULT, UiSession.DEFAULT_RANKING, 1L);
        UiSession second = store.rotate(TestData.ALICE, first, HistoryFilter.DEFAULT, UiSession.DEFAULT_RANKING, 1L);
        assertNotNull(second);
        assertNotEquals(first.token(), second.token());
        assertEquals(second.token(), store.current(TestData.ALICE).token());
        assertEquals(null, store.rotate(TestData.ALICE, first, HistoryFilter.DEFAULT, UiSession.DEFAULT_RANKING, 1L));
        assertTrue(store.invalidate(TestData.ALICE, second));
        assertEquals(null, store.current(TestData.ALICE));
    }

    @Test
    void staleTokenProducesNoticeAndFreshHomeWithoutSideEffects() {
        FakeUiContext ctx = new FakeUiContext(TestData.ALICE, List.of(TestData.player(
                TestData.ALICE, "Alice", false,
                TestData.death(TestData.ALICE, "Alice", 10_000L, "fall"))));
        UiResult opened = UiActions.handle(new UiRequest(TestData.ALICE, "home"), ctx);
        String oldToken = ctx.token();
        assertInstanceOf(UiResult.Open.class, opened);
        assertFalse(oldToken.isEmpty());

        ctx.nowMillis += UiSession.TTL_MILLIS;
        UiResult result = UiActions.handle(new UiRequest(
                TestData.ALICE, "deathcensus:ui/respect", oldToken, 1,
                TestData.ALICE.toString(), 10_000L, Map.of()), ctx);
        assertInstanceOf(HomeView.class, assertInstanceOf(UiResult.Open.class, result).view());
        assertTrue(ctx.notices.contains(UiText.SESSION_EXPIRED_KEY));
        assertTrue(ctx.calls.isEmpty());
        assertNotEquals(oldToken, ctx.token());
    }

    @Test
    void mutationInvalidatesTokenSoReplayCannotRepeatIt() {
        FakeUiContext ctx = new FakeUiContext(TestData.ALICE, List.of(TestData.player(
                TestData.ALICE, "Alice", false,
                TestData.death(TestData.ALICE, "Alice", 10_000L, "fall"))));
        UiActions.handle(new UiRequest(TestData.ALICE, "home"), ctx);
        String token = ctx.token();
        UiRequest respect = new UiRequest(
                TestData.ALICE, "respect", token, 1, TestData.ALICE.toString(), 10_000L, Map.of());
        UiResult.SideEffect sideEffect = assertInstanceOf(UiResult.SideEffect.class, UiActions.handle(respect, ctx));
        assertTrue(sideEffect.applied());
        assertTrue(ctx.token().isEmpty());

        UiActions.handle(respect, ctx);
        assertEquals(1, ctx.calls.size());
        assertTrue(ctx.notices.contains(UiText.SESSION_EXPIRED_KEY));
    }

    @Test
    void navigationRotationKeepsSessionFilter() {
        FakeUiContext ctx = new FakeUiContext(TestData.ALICE, List.of(TestData.player(
                TestData.ALICE, "Alice", false,
                TestData.death(TestData.ALICE, "Alice", 10_000L, "fall"),
                TestData.death(TestData.ALICE, "Alice", 11_000L, "lava"))));
        UiActions.handle(new UiRequest(TestData.ALICE, "home"), ctx);
        String searchToken = ctx.token();
        UiRequest search = new UiRequest(TestData.ALICE, "history_search", searchToken, 1, "", -1L,
                Map.of("in_query", "lava"));
        HistoryView view = assertInstanceOf(HistoryView.class,
                assertInstanceOf(UiResult.Open.class, UiActions.handle(search, ctx)).view());
        assertEquals("lava", view.filter().query());
        assertNotEquals(searchToken, view.sessionToken());

        UiRequest page = new UiRequest(TestData.ALICE, "history_page", view.sessionToken(), 99, "", -1L, Map.of());
        HistoryView next = assertInstanceOf(HistoryView.class,
                assertInstanceOf(UiResult.Open.class, UiActions.handle(page, ctx)).view());
        assertEquals("lava", next.filter().query());
        assertEquals(1, next.page());
        assertNotEquals(view.sessionToken(), next.sessionToken());
    }
}
