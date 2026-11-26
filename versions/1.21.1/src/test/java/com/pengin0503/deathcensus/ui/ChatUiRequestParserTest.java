package com.pengin0503.deathcensus.ui;

import com.pengin0503.deathcensus.PlayerDeaths;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The 1.21.1 /dc ui tree must encode the PR-1 UiActionRef protocol exactly. */
class ChatUiRequestParserTest {
    private static final UUID VIEWER = TestData.ALICE;
    private static final String TOKEN = "11111111-2222-3333-4444-555555555555";

    private static UiRequest parsed(String... args) {
        return ChatUiRequestParser.parse(VIEWER, args).orElseThrow();
    }

    @Test
    void homeAndAdminEncodeBareActions() {
        UiRequest home = parsed("home");
        assertEquals("home", home.action());
        assertEquals(VIEWER, home.viewer());
        assertEquals(1, home.page());
        assertTrue(home.inputs().containsKey("p"));
        assertEquals("admin", parsed("/dc", "ui", "admin").action());
    }

    @Test
    void historyPageCarriesTokenAndPage() {
        UiRequest request = parsed("history", "3", TOKEN);
        assertEquals("history", request.action());
        assertEquals(3, request.page());
        assertEquals(TOKEN, request.token());
        assertEquals(TOKEN, request.inputs().get("s"));
        assertEquals("3", request.inputs().get("p"));
        assertEquals(1, parsed("history").page());
    }

    @Test
    void historyFilterEncodesAllInputs() {
        UiRequest request = parsed("history", "filter", "7d", "fall", "Steve", "fell", "again");
        assertEquals("history_search", request.action());
        assertEquals("7d", request.inputs().get("in_period"));
        assertEquals("fall", request.inputs().get("in_category"));
        assertEquals("Steve fell again", request.inputs().get("in_query"));
        assertEquals("all", request.inputs().get("in_dimension"));
    }

    @Test
    void dimensionIdInCategorySlotBecomesDimensionInput() {
        UiRequest request = parsed("history", "filter", "all", "minecraft:the_nether");
        assertEquals("all", request.inputs().get("in_category"));
        assertEquals("minecraft:the_nether", request.inputs().get("in_dimension"));
        assertEquals("", request.inputs().get("in_query"));
    }

    @Test
    void targetActionsCarryUuidEpochAndToken() {
        UiRequest detail = parsed("detail", TestData.BOB.toString(), "1700000000000", TOKEN);
        assertEquals("detail", detail.action());
        assertEquals(TestData.BOB.toString(), detail.targetUuid());
        assertEquals(1_700_000_000_000L, detail.deathEpoch());
        assertEquals(TOKEN, detail.token());
        assertEquals("1700000000000", detail.inputs().get("d"));
        assertEquals(TestData.BOB.toString(), detail.inputs().get("t"));
        assertEquals("respect", parsed("respect", TestData.BOB.toString(), "1700000000000", TOKEN).action());
    }

    @Test
    void deleteConfirmIsASeparateAction() {
        UiRequest ask = parsed("delete", TestData.BOB.toString(), "42");
        UiRequest confirm = parsed("delete", TestData.BOB.toString(), "42", "confirm", TOKEN);
        assertEquals("delete_ask", ask.action());
        assertEquals("delete_confirm", confirm.action());
        assertEquals(TOKEN, confirm.token());
        assertEquals(42L, confirm.deathEpoch());
        assertFalse(confirm.inputs().containsKey("index")); // no index is trusted from a command
    }

    @Test
    void excuseOpenAndSaveUseTheSelectedDeath() {
        UiRequest open = parsed("excuse", VIEWER.toString(), "100", TOKEN);
        UiRequest save = parsed("excuse", VIEWER.toString(), "100", TOKEN, "save", "my", "bad", "lumo");
        assertEquals("excuse_open", open.action());
        assertEquals("excuse_save", save.action());
        assertEquals("my bad lumo", save.inputs().get("in_excuse"));
        assertEquals(100L, save.deathEpoch());
        assertEquals("excuse_save",
                parsed("excuse", VIEWER.toString(), "100", "save", "no token").action());
    }

    @Test
    void rankingsEncodeTypePeriodAndPagination() {
        UiRequest selection = parsed("rankings", "survival", "30d", "2", TOKEN);
        assertEquals("rankings", selection.action());
        assertEquals(2, selection.page());
        assertEquals("survival", selection.inputs().get("in_type"));
        assertEquals("30d", selection.inputs().get("in_period"));
        UiRequest next = parsed("rankings", "page", "4", TOKEN);
        assertEquals("ranking_page", next.action());
        assertEquals(4, next.page());
        assertEquals("rankings", parsed("rankings").action());
    }

    @Test
    void privacyToggleEncodesBooleanInput() {
        UiRequest hide = parsed("privacy", "optout");
        UiRequest show = parsed("privacy", "optin", TOKEN);
        assertEquals("privacy", parsed("privacy").action());
        assertEquals("privacy_save", hide.action());
        assertEquals("true", hide.inputs().get("in_optout"));
        assertEquals("false", show.inputs().get("in_optout"));
        assertEquals(TOKEN, show.token());
    }

    @Test
    void allFourPrivacyTogglesEncodeTheirBoundedInputs() {
        UiRequest coords = parsed("privacy", "coords", "on", TOKEN);
        assertEquals("privacy_save", coords.action());
        assertEquals("true", coords.inputs().get("in_hide_coords"));
        assertEquals(TOKEN, coords.token());
        assertEquals("false", parsed("privacy", "opponent", "off").inputs().get("in_hide_opponent"));
        assertEquals("true", parsed("privacy", "discord", "enabled").inputs().get("in_discord_optout"));
        assertEquals("false", parsed("privacy", "full", "0").inputs().get("in_optout"));
        assertTrue(ChatUiRequestParser.parse(VIEWER, "privacy", "coords", "maybe").isEmpty());
        assertTrue(ChatUiRequestParser.parse(VIEWER, "privacy", "nope", "on").isEmpty());
    }

    @Test
    void malformedCommandsAreRejected() {
        assertTrue(ChatUiRequestParser.parse(VIEWER, "unknown").isEmpty());
        assertTrue(ChatUiRequestParser.parse(VIEWER, "detail", "not-a-uuid", "1").isEmpty());
        assertTrue(ChatUiRequestParser.parse(VIEWER, "detail", VIEWER.toString(), "-1").isEmpty());
        assertTrue(ChatUiRequestParser.parse(VIEWER, "history", "0").isEmpty());
        assertTrue(ChatUiRequestParser.parse(VIEWER, "history", "filter", "bogus").isEmpty());
        assertTrue(ChatUiRequestParser.parse(VIEWER, "rankings", "bogus", "all").isEmpty());
        assertTrue(ChatUiRequestParser.parse(VIEWER, "excuse", VIEWER.toString(), "1", "save", "x".repeat(101)).isEmpty());
        assertTrue(ChatUiRequestParser.parse(null, "home").isEmpty());
        assertTrue(ChatUiRequestParser.parse(VIEWER, List.of()).isEmpty());
    }

    @Test
    void parsedRequestDispatchesThroughCommonActions() {
        FakeUiContext ctx = new FakeUiContext(VIEWER, List.<PlayerDeaths>of());
        UiResult home = UiActions.handle(parsed("home"), ctx);
        assertInstanceOf(HomeView.class, ((UiResult.Open) home).view());

        UiResult history = UiActions.handle(parsed("history", "filter", "all", "fall", "Steve"), ctx);
        HistoryView view = assertInstanceOf(HistoryView.class, ((UiResult.Open) history).view());
        assertEquals("fall", view.filter().category());
        assertEquals("Steve", view.filter().query());
        assertFalse(view.sessionToken().isEmpty());

        UiResult privacy = UiActions.handle(parsed("privacy", "optin", view.sessionToken()), ctx);
        UiResult.SideEffect effect = assertInstanceOf(UiResult.SideEffect.class, privacy);
        assertTrue(effect.applied());
        assertTrue(ctx.calls.contains("optout:false"));
    }
}
