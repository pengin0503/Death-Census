package com.pengin0503.deathcensus.ui;

import com.pengin0503.deathcensus.PlayerDeaths;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiActionsValidationTest {
    private FakeUiContext context() {
        FakeUiContext ctx = new FakeUiContext(TestData.ALICE, List.of(TestData.player(
                TestData.ALICE, "Alice", false,
                TestData.death(TestData.ALICE, "Alice", 1_000L, "fall"))));
        ctx.newSession(HistoryFilter.DEFAULT, UiSession.DEFAULT_RANKING);
        return ctx;
    }

    @Test
    void actionIdentifiersAreStrict() {
        assertEquals("home", UiActions.actionFromIdentifier("deathcensus:ui/home").orElseThrow());
        assertEquals("history_search", UiActions.actionFromIdentifier(" deathcensus:ui/history_search ").orElseThrow());
        java.util.List<String> malformedIds = new java.util.ArrayList<>(List.of(
                "minecraft:ui/home", "deathcensus:home", "ui/home",
                "deathcensus:ui/nope", "deathcensus:ui/home/extra", ""));
        malformedIds.add(null);
        for (String malformed : malformedIds) {
            assertTrue(UiActions.actionFromIdentifier(malformed).isEmpty(), "accepted: " + malformed);
        }
        assertTrue(UiActions.isKnownAction("history_search"));
        assertFalse(UiActions.isKnownAction("nope"));
        assertEquals("history", UiActions.resolveAction("deathcensus:ui/history"));
        assertEquals("history", UiActions.resolveAction("history"));
        assertEquals("deathcensus:ui/home", new UiActionRef("home").identifier());
    }

    @Test
    void oversizedPayloadIsRejectedWithoutSideEffects() {
        FakeUiContext ctx = context();
        Map<String, String> huge = Map.of("in_query", "x".repeat(1024));
        assertFalse(UiActions.payloadWithinLimit(huge));
        assertEquals("payload_too_large", UiActions.validatePayload(huge).orElseThrow());

        UiResult result = UiActions.handle(new UiRequest(
                TestData.ALICE, "deathcensus:ui/history_search", ctx.token(), 1, "", -1L, huge), ctx);
        UiResult.NoChange noChange = assertInstanceOf(UiResult.NoChange.class, result);
        assertEquals("payload_too_large", noChange.reason());
        assertTrue(ctx.notices.isEmpty());
        assertTrue(ctx.calls.isEmpty());
    }

    @Test
    void malformedFieldsAreRejectedByBounds() {
        assertEquals("invalid_period", UiActions.validatePayload(Map.of("in_period", "bogus")).orElseThrow());
        assertEquals("invalid_page", UiActions.validatePayload(Map.of("p", "0")).orElseThrow());
        assertEquals("invalid_page", UiActions.validatePayload(Map.of("p", "10001")).orElseThrow());
        assertEquals("invalid_uuid", UiActions.validatePayload(Map.of("t", "not-a-uuid")).orElseThrow());
        assertEquals("invalid_epoch", UiActions.validatePayload(Map.of("d", "-1")).orElseThrow());
        assertEquals("invalid_excuse", UiActions.validatePayload(Map.of("in_excuse", "x".repeat(101))).orElseThrow());
        assertEquals("invalid_query", UiActions.validatePayload(Map.of("in_query", "q".repeat(65))).orElseThrow());
        assertEquals("invalid_optout", UiActions.validatePayload(Map.of("in_optout", "maybe")).orElseThrow());
        assertEquals("invalid_hide_coords",
                UiActions.validatePayload(Map.of("in_hide_coords", "maybe")).orElseThrow());
        assertEquals("invalid_hide_opponent",
                UiActions.validatePayload(Map.of("in_hide_opponent", "x")).orElseThrow());
        assertEquals("invalid_discord_optout",
                UiActions.validatePayload(Map.of("in_discord_optout", "")).orElseThrow());
        assertTrue(UiActions.validatePayload(Map.of(
                "in_optout", "true", "in_hide_coords", "false",
                "in_hide_opponent", "1", "in_discord_optout", "0")).isEmpty());
        assertEquals("invalid_ranking_type", UiActions.validatePayload(Map.of("in_type", "bogus")).orElseThrow());
        assertTrue(UiActions.validatePayload(Map.of("in_excuse", "x".repeat(100))).isEmpty());
    }

    @Test
    void categoryAndDimensionMustBeKnownInLiveData() {
        FakeUiContext ctx = context();
        assertTrue(UiActions.validateInputs(Map.of("in_category", "fall"), ctx).isEmpty());
        assertEquals("invalid_category",
                UiActions.validateInputs(Map.of("in_category", "mystery"), ctx).orElseThrow());
        assertTrue(UiActions.validateInputs(Map.of("in_dimension", "minecraft:overworld"), ctx).isEmpty());
        assertEquals("invalid_dimension",
                UiActions.validateInputs(Map.of("in_dimension", "minecraft:nether"), ctx).orElseThrow());
        assertTrue(UiActions.knownDimensions(ctx).contains("minecraft:overworld"));
    }

    @Test
    void unknownActionsAndOutOfRangePagesAreNoChange() {
        FakeUiContext ctx = context();
        UiResult unknown = UiActions.handle(new UiRequest(TestData.ALICE, "deathcensus:ui/nope", ctx.token(), 1, "", -1L, Map.of()), ctx);
        assertEquals("unknown_action", assertInstanceOf(UiResult.NoChange.class, unknown).reason());
        UiResult wrongNamespace = UiActions.handle(new UiRequest(TestData.ALICE, "minecraft:ui/home", ctx.token(), 1, "", -1L, Map.of()), ctx);
        assertEquals("unknown_action", assertInstanceOf(UiResult.NoChange.class, wrongNamespace).reason());
        UiResult badPage = UiActions.handle(new UiRequest(TestData.ALICE, "home", ctx.token(), 0, "", -1L, Map.of()), ctx);
        assertEquals("invalid_page", assertInstanceOf(UiResult.NoChange.class, badPage).reason());
        assertEquals("invalid_request", assertInstanceOf(UiResult.NoChange.class, UiActions.handle(null, ctx)).reason());
        assertTrue(ctx.calls.isEmpty());
    }

    @Test
    void numericAndBooleanParsersAreBounded() {
        assertEquals(1, UiActions.parsePage("1").orElseThrow());
        assertTrue(UiActions.parsePage("10001").isEmpty());
        assertEquals(0L, UiActions.parseEpoch("0").orElseThrow());
        assertTrue(UiActions.parseEpoch("-1").isEmpty());
        assertEquals(Boolean.TRUE, UiActions.parseBoolean("1").orElseThrow());
        assertEquals(Boolean.FALSE, UiActions.parseBoolean("false").orElseThrow());
        assertTrue(UiActions.parseBoolean("yes").isEmpty());
    }
}
