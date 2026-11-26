package com.pengin0503.deathcensus;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Version-side checks for DESIGN 1.3.0 §1/§4: defaults, channel routing and secret hygiene. */
class DiscordNotifierTest {
    private static final String PRIMARY = "https://discord.com/api/webhooks/111/aaa";
    private static final String DIGEST = "https://discord.com/api/webhooks/222/bbb";

    @Test
    void retryConfigAccessorsReturnDefaultsWithoutLoadedConfig() {
        assertEquals(4, Config.discordRetryMaxAttempts());
        assertEquals(1000, Config.discordRetryBaseDelayMillis());
    }

    @Test
    void digestWebhookUrlDefaultsToEmpty() {
        assertEquals("", Config.discordDigestWebhookUrl());
    }

    /** DESIGN 1.3.0 §3: both realtime filters return their defaults before config load. */
    @Test
    void notificationFilterConfigDefaultsAllowEveryDeath() {
        assertTrue(Config.discordNotifyDimensions().isEmpty());
        assertEquals(DiscordLogic.PvpFilterMode.ALL, Config.discordNotifyPvpMode());
        assertEquals("all", Config.discordNotifyPvpMode().configId());
    }

    @Test
    void notificationFilterValidatorsRejectBlankDimensionsAndUnknownModes() {
        assertTrue(Config.validateDimensionId("minecraft:the_nether"));
        assertFalse(Config.validateDimensionId(""));
        assertFalse(Config.validateDimensionId("   "));
        assertFalse(Config.validateDimensionId(" minecraft:the_end"));
        assertFalse(Config.validateDimensionId("minecraft:the end"));
        assertFalse(Config.validateDimensionId(42));
        assertTrue(Config.validatePvpMode("all"));
        assertTrue(Config.validatePvpMode("pvp_only"));
        assertTrue(Config.validatePvpMode("off"));
        assertFalse(Config.validatePvpMode("PVP_ONLY"));
        assertFalse(Config.validatePvpMode("pvp"));
        assertFalse(Config.validatePvpMode(""));
        assertFalse(Config.validatePvpMode(null));
    }

    @Test
    void payloadJsonStillCapsContentAndDisablesMentions() {
        JsonObject body = JsonParser.parseString(
                DiscordNotifier.payloadJson("x".repeat(2500))).getAsJsonObject();
        String content = body.get("content").getAsString();
        assertEquals(2000, content.length());
        assertEquals(0, body.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
    }

    @Test
    void channelsResolveIndependentlyAndInvalidDigestDisablesOnlyDigest() {
        assertEquals(URI.create(PRIMARY),
                DiscordNotifier.webhookUri(WebhookChannel.REALTIME, PRIMARY, DIGEST));
        assertEquals(URI.create(DIGEST),
                DiscordNotifier.webhookUri(WebhookChannel.DIGEST, PRIMARY, DIGEST));

        // An empty digest value falls back to the primary URL; only DIGEST does this.
        assertEquals(URI.create(PRIMARY),
                DiscordNotifier.webhookUri(WebhookChannel.DIGEST, PRIMARY, ""));
        assertEquals(URI.create(PRIMARY),
                DiscordNotifier.webhookUri(WebhookChannel.DIGEST, PRIMARY, "   "));

        // §12 Q9: a set-but-invalid digest URL disables digest sending, with no fallback.
        String typo = "https://example.com/not-a-discord-webhook";
        assertNull(DiscordNotifier.webhookUri(WebhookChannel.DIGEST, PRIMARY, typo));
        // Realtime is unaffected by anything configured on the digest channel.
        assertEquals(URI.create(PRIMARY),
                DiscordNotifier.webhookUri(WebhookChannel.REALTIME, PRIMARY, typo));
        // Realtime never borrows the digest URL when its own primary is invalid.
        assertNull(DiscordNotifier.webhookUri(WebhookChannel.REALTIME, "not-a-url", DIGEST));

        // Neither channel sends when nothing is configured.
        assertNull(DiscordNotifier.webhookUri(WebhookChannel.REALTIME, "", ""));
        assertNull(DiscordNotifier.webhookUri(WebhookChannel.DIGEST, "", ""));
    }

    @Test
    void doctorStatusNeverContainsAWebhookUrl() {
        String secret = "https://discord.com/api/webhooks/999/supersecret";
        assertEquals("not configured", CensusCommands.webhookStatus(""));
        assertEquals("not configured", CensusCommands.webhookStatus(null));
        assertEquals("configured", CensusCommands.webhookStatus(PRIMARY));
        assertEquals("invalid URL", CensusCommands.webhookStatus("https://example.com/x"));
        for (String value : new String[]{"", PRIMARY, "https://example.com/x", secret}) {
            String status = CensusCommands.webhookStatus(value);
            assertTrue(status.equals("not configured") || status.equals("configured")
                    || status.equals("invalid URL"));
            assertFalse(status.contains("http"), "doctor status leaked a URL");
            assertFalse(status.contains("supersecret"), "doctor status leaked a URL");
        }
    }

    @Test
    void discordtestReportsNoChannelConfiguredByDefault() {
        assertFalse(DiscordNotifier.sendTest());
        assertFalse(DiscordNotifier.sendTest(WebhookChannel.REALTIME));
        assertFalse(DiscordNotifier.sendTest(WebhookChannel.DIGEST));
    }

    /**
     * No log statement in version sources may interpolate a URL-like value or
     * embed a URL literal. String literals are stripped before the identifier
     * check so harmless words such as "webhook queue" are allowed.
     */
    @Test
    void noLogStatementContainsOrInterpolatesAUrl() throws IOException {
        String sourceDir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(sourceDir, "deathcensus.mainSourceDir system property is not set");
        Pattern logCall = Pattern.compile(
                "LOGGER\\.(?:warn|info|error|debug|trace)\\(([^;]*)\\);", Pattern.DOTALL);
        Pattern stringLiteral = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
        Pattern urlIdentifier = Pattern.compile("(?i)\\b(uri|url|resolved|webhookUrl|digestUrl|primary)\\b");
        Pattern urlLiteral = Pattern.compile("(?i)https?://");
        int checked = 0;
        try (Stream<Path> stream = Files.walk(Path.of(sourceDir))) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher calls = logCall.matcher(Files.readString(file));
                while (calls.find()) {
                    String args = calls.group(1);
                    checked++;
                    assertFalse(urlLiteral.matcher(args).find(), file + ": log statement contains a URL literal");
                    String identifiers = stringLiteral.matcher(args).replaceAll("\"\"");
                    assertFalse(urlIdentifier.matcher(identifiers).find(),
                            file + ": log statement interpolates a URL-like value");
                }
            }
        }
        assertTrue(checked >= 5, "expected to inspect log statements in version sources");
    }
}
