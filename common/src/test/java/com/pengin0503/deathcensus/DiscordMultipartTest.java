package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure multipart-body tests: no HttpClient, no network, no sleeping. */
class DiscordMultipartTest {

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
    }
}
