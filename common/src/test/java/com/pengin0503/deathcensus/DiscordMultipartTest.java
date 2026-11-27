package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure multipart-body tests: no HttpClient, no network, no sleeping. */
class DiscordMultipartTest {
    private static final String CONTENT_TYPE_PREFIX = "multipart/form-data; boundary=";

    private static String boundaryOf(DiscordMultipart.Body body) {
        return boundaryOf(body.contentType());
    }

    private static String boundaryOf(String contentType) {
        assertTrue(contentType.startsWith(CONTENT_TYPE_PREFIX));
        return contentType.substring(CONTENT_TYPE_PREFIX.length());
    }

    @Test
    void bodyHasPayloadJsonAndFilePartsInOrder() {
        DiscordMultipart.Body body = DiscordMultipart.compose(
                "{\"content\":\"hello\"}", "season-20240101-20240102.html", "<html>ok</html>");

        assertTrue(body.contentType().startsWith("multipart/form-data; boundary="));
        int jsonPart = body.body().indexOf("name=\"payload_json\"");
        int filePart = body.body().indexOf("name=\"files[0]\"");
        int html = body.body().indexOf("<html>ok</html>");
        assertTrue(jsonPart > 0);
        assertTrue(filePart > jsonPart);
        assertTrue(html > filePart);
        assertTrue(body.body().contains("Content-Disposition: form-data; name=\"files[0]\"; "
                + "filename=\"season-20240101-20240102.html\""));
        assertTrue(body.body().contains("Content-Type: text/html; charset=utf-8"));
        assertTrue(body.body().endsWith("--\r\n"));
        assertTrue(body.body().contains("{\"content\":\"hello\"}\r\n--"));
    }

    @Test
    void bodyNeverCarriesAWebhookUrlOrSecret() {
        String secret = "https://discord.com/api/webhooks/123/supersecret";
        DiscordMultipart.Body body = DiscordMultipart.compose("{\"content\":\"x\"}", "season.html", "<html>x</html>");

        assertFalse(body.body().contains(secret));
        assertFalse(body.contentType().contains(secret));
    }

    @Test
    void attachmentNameUsesSeasonRangeAndDisplayNameZone() {
        SeasonRange range = SeasonRange.parse("2024-01-01", "2024-01-02", ZoneOffset.UTC).range();

        assertEquals("season-20240101-20240102.html",
                DiscordMultipart.attachmentName(range, ZoneOffset.UTC));
        assertEquals("season-20240101-20240102.html",
                DiscordMultipart.attachmentName(range, java.time.ZoneId.of("Asia/Tokyo")));
        assertEquals("season-20231231-20240101.html",
                DiscordMultipart.attachmentName(range, java.time.ZoneId.of("America/Los_Angeles")));
        assertEquals("season.html", DiscordMultipart.attachmentName(null, ZoneOffset.UTC));
    }

    @Test
    void attachmentCapIsOneMegabyte() {
        assertFalse(DiscordMultipart.tooLarge(DiscordMultipart.MAX_ATTACHMENT_BYTES));
        assertTrue(DiscordMultipart.tooLarge(DiscordMultipart.MAX_ATTACHMENT_BYTES + 1));
        assertEquals("1 MB", DiscordMultipart.sizeLimitLabel());
    }

    @Test
    void nullInputsAreSafe() {
        DiscordMultipart.Body body = DiscordMultipart.compose(null, null, null);

        assertTrue(body.body().contains("filename=\"season.html\""));
        assertTrue(body.body().contains("\r\n\r\n\r\n--"));
        String boundary = boundaryOf(body);
        assertTrue(boundary.startsWith("----DeathCensusBoundary"));
        assertTrue(body.body().startsWith("--" + boundary + "\r\n"));
        assertTrue(body.body().endsWith("--" + boundary + "--\r\n"));
    }

    @Test
    void twoComposeCallsUseDifferentBoundariesAndContentTypes() {
        DiscordMultipart.Body first = DiscordMultipart.compose(
                "{\"content\":\"x\"}", "season.html", "<html>x</html>");
        DiscordMultipart.Body second = DiscordMultipart.compose(
                "{\"content\":\"x\"}", "season.html", "<html>x</html>");

        assertNotEquals(first.contentType(), second.contentType());
        assertNotEquals(boundaryOf(first), boundaryOf(second));
        assertTrue(boundaryOf(first).startsWith("----DeathCensusBoundary"));
        assertTrue(boundaryOf(second).startsWith("----DeathCensusBoundary"));
    }

    @Test
    void eachBodyBoundaryMatchesItsContentTypeAndDelimiters() {
        String json = "{\"content\":\"x\"}";
        String name = "season-20240101-20240102.html";
        String html = "<html>ok</html>";
        DiscordMultipart.Body body = DiscordMultipart.compose(json, name, html);
        String boundary = boundaryOf(body);

        assertFalse(json.contains(boundary));
        assertFalse(name.contains(boundary));
        assertFalse(html.contains(boundary));
        assertTrue(body.body().startsWith("--" + boundary + "\r\n"));
        assertTrue(body.body().contains("\r\n--" + boundary + "\r\n"));
        assertTrue(body.body().endsWith("--" + boundary + "--\r\n"));
    }

    @Test
    void boundaryForgedInHtmlIsRegenerated() {
        String forged = "----DeathCensusBoundaryFORGED";
        String html = "<html>last words\r\n--" + forged + "\r\n</html>";
        // The injected generator offers the forged token first, then a clean one.
        Iterator<String> candidates = List.of(forged, "----DeathCensusBoundaryCLEAN").iterator();
        DiscordMultipart.Body body = DiscordMultipart.compose(
                "{\"content\":\"x\"}", "season.html", html, candidates::next);

        String used = boundaryOf(body);
        assertEquals("----DeathCensusBoundaryCLEAN", used);
        assertNotEquals(forged, used);
        assertFalse(body.contentType().contains(forged));
        assertTrue(body.body().contains(html));
        assertTrue(body.body().endsWith("--" + used + "--\r\n"));
    }

    @Test
    void boundaryForgedInJsonOrFileNameIsRegenerated() {
        String forgedJson = "----DeathCensusBoundaryINJSON";
        Iterator<String> jsonCandidates =
                List.of(forgedJson, "----DeathCensusBoundaryJSONSAFE").iterator();
        DiscordMultipart.Body jsonBody = DiscordMultipart.compose(
                "{\"content\":\"" + forgedJson + "\"}", "season.html", "<html>x</html>", jsonCandidates::next);

        assertEquals("----DeathCensusBoundaryJSONSAFE", boundaryOf(jsonBody));
        assertFalse(jsonBody.contentType().contains(forgedJson));
        assertTrue(jsonBody.body().contains(forgedJson));

        String forgedName = "----DeathCensusBoundaryINNAME";
        Iterator<String> nameCandidates =
                List.of(forgedName, "----DeathCensusBoundaryNAMESAFE").iterator();
        DiscordMultipart.Body nameBody = DiscordMultipart.compose(
                "{\"content\":\"x\"}", forgedName, "<html>x</html>", nameCandidates::next);

        assertEquals("----DeathCensusBoundaryNAMESAFE", boundaryOf(nameBody));
        assertFalse(nameBody.contentType().contains(forgedName));
    }

    @Test
    void contentTypeMethodStillReturnsAValidContentTypeValue() {
        String contentType = DiscordMultipart.contentType();

        assertTrue(contentType.startsWith(CONTENT_TYPE_PREFIX));
        assertTrue(boundaryOf(contentType).startsWith("----DeathCensusBoundary"));
    }
}
