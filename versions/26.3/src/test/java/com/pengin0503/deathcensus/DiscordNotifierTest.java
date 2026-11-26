package com.pengin0503.deathcensus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pengin0503.deathcensus.DiscordLogic.SeasonSection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Version-side checks for DESIGN 1.3.0 §1/§2/§4: defaults, batching gate, routing and secret hygiene. */
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

    /** DESIGN 1.3.0 §2 / §12 Q4: adopted defaults for the realtime batch window and cap. */
    @Test
    void batchConfigAccessorsReturnDefaultsWithoutLoadedConfig() {
        assertEquals(1500, Config.discordBatchWindowMillis());
        assertEquals(10, Config.discordBatchMaxDeaths());
    }

    /**
     * DESIGN 1.3.0 §2 / DESIGN §8: the opt-out gate is applied before a death is
     * buffered, so an opted-out player is never named in a batch. No new buffer
     * test seam is needed: assert structurally that every call site of
     * {@code notifyDeath} in version sources sits behind the existing gate.
     */
    @Test
    void optedOutDeathsNeverReachTheBatchBuffer() throws IOException {
        String sourceDir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(sourceDir, "deathcensus.mainSourceDir system property is not set");
        Pattern notifyCall = Pattern.compile("DiscordNotifier\\.notifyDeath\\(");
        int guardedCalls = 0;
        try (Stream<Path> stream = Files.walk(Path.of(sourceDir))) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher calls = notifyCall.matcher(source);
                while (calls.find()) {
                    String before = source.substring(Math.max(0, calls.start() - 240), calls.start());
                    assertTrue(before.contains("!pc.optOutPublic"),
                            file + ": notifyDeath is not preceded by the opt-out gate");
                    guardedCalls++;
                }
            }
        }
        assertTrue(guardedCalls >= 1, "expected the opt-out-gated notifyDeath call in DeathTracker");
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

    /** PR-6: season posts alone carry an embed, and the payload never carries a secret. */
    @Test
    void seasonPayloadJsonHasEmbedFieldsAndNoWebhookUrl() {
        String secret = "https://discord.com/api/webhooks/999/supersecret";
        List<SeasonSection> sections = List.of(
                new SeasonSection("Season digest", "5 death(s), 2026-01-01 → 2026-01-02"),
                new SeasonSection("Awards", "All 17 categories: Alice (1 qualifiers)"));

        JsonObject body = JsonParser.parseString(
                DiscordNotifier.seasonPayloadJson("x".repeat(2500), sections)).getAsJsonObject();

        assertEquals(2000, body.get("content").getAsString().length());
        assertEquals(0, body.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
        JsonArray embeds = body.getAsJsonArray("embeds");
        assertEquals(1, embeds.size());
        JsonObject embed = embeds.get(0).getAsJsonObject();
        assertEquals("Death Census — Season summary", embed.get("title").getAsString());
        JsonArray fields = embed.getAsJsonArray("fields");
        assertEquals(2, fields.size());
        assertEquals("Season digest", fields.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("5 death(s), 2026-01-01 → 2026-01-02",
                fields.get(0).getAsJsonObject().get("value").getAsString());
        assertFalse(fields.get(0).getAsJsonObject().get("inline").getAsBoolean());
        assertFalse(body.toString().contains(secret));
    }

    @Test
    void seasonEmbedFieldsRespectDiscordLimits() {
        List<SeasonSection> sections = List.of(new SeasonSection("n".repeat(300), "v".repeat(2000)));
        JsonObject embed = JsonParser.parseString(
                DiscordNotifier.seasonPayloadJson("content", sections))
                .getAsJsonObject().getAsJsonArray("embeds").get(0).getAsJsonObject();
        JsonObject field = embed.getAsJsonArray("fields").get(0).getAsJsonObject();

        assertEquals(256, field.get("name").getAsString().length());
        assertEquals(1024, field.get("value").getAsString().length());
    }

    /** No webhook is configured in tests: this returns before any HttpClient is created. */
    @Test
    void seasonPostWithoutWebhookDoesNotSendOrTouchDigestSchedule() {
        assertEquals(DiscordNotifier.SeasonPostResult.NO_CHANNEL,
                DiscordNotifier.postSeason(WebhookChannel.DIGEST, "content", List.of(), null, null));
    }

    @Test
    void seasonPostAndCommandNeverWritePeriodicDigestState() throws IOException {
        String sourceDir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(sourceDir, "deathcensus.mainSourceDir system property is not set");
        Path packageDir = Path.of(sourceDir, "com", "pengin0503", "deathcensus");
        String notifier = Files.readString(packageDir.resolve("DiscordNotifier.java"));
        String commands = Files.readString(packageDir.resolve("CensusCommands.java"));
        String export = Files.readString(packageDir.resolve("CensusExport.java"));

        String postSeason = between(notifier, "public static SeasonPostResult postSeason(", "private static void notifyFailure");
        assertTrue(postSeason.contains("webhookUri(channel)"));
        assertFalse(postSeason.contains("setLastDigestEpochMillis"));
        assertFalse(postSeason.contains("tickCounter"));

        String digestSeason = between(commands, "private static int digestSeason(", "public static final int DELETE_OK");
        assertTrue(digestSeason.contains("SeasonRange.parse"));
        assertTrue(digestSeason.contains("DiscordNotifier.postSeason(WebhookChannel.DIGEST"));
        assertFalse(digestSeason.contains("setLastDigestEpochMillis"));
        assertFalse(digestSeason.contains("checkAndSendDigest"));

        String html = between(export, "public static Path writeSeasonHtml(", "public static Path writeBackup(");
        assertTrue(html.contains("!player.optOutPublic()"));
        assertTrue(html.contains("CensusStats.filterRange"));
    }

    @Test
    void seasonCommandUsesDigestGateAndWordArguments() throws IOException {
        String sourceDir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(sourceDir, "deathcensus.mainSourceDir system property is not set");
        String commands = Files.readString(
                Path.of(sourceDir, "com", "pengin0503", "deathcensus", "CensusCommands.java"));
        int index = commands.indexOf("literal(\"digest\")");
        assertTrue(index > 0, "missing /dc digest subtree");
        String subtree = commands.substring(index, Math.min(commands.length(), index + 1400));
        assertTrue(subtree.contains("DeathCensusPermission.DIGEST"));
        assertTrue(subtree.contains("PermissionBridge.requires"));
        assertTrue(subtree.contains("literal(\"season\")"));
        assertTrue(subtree.contains("argument(\"from\", StringArgumentType.word())"));
        assertTrue(subtree.contains("argument(\"to\", StringArgumentType.word())"));
        assertTrue(subtree.contains("literal(\"html\")"));
    }

    /** Fake sender: exercises the retry/multipart seam with zero HTTP and zero sleeping. */
    @Test
    void multipartRetryUsesFakeSenderWithoutLiveHttp() {
        DiscordMultipart.Body body = DiscordMultipart.compose(
                "{\"content\":\"x\"}", "season.html", "<html>x</html>");
        List<String> contentTypes = new ArrayList<>();
        List<Long> delays = new ArrayList<>();
        int[] calls = {0};
        DiscordNotifier.MultipartSender fake = (target, multipartBody, contentType) -> {
            contentTypes.add(contentType);
            calls[0]++;
            assertTrue(multipartBody.contains("<html>x</html>"));
            return calls[0] < 2 ? new WebhookResponse(500, null) : new WebhookResponse(204, null);
        };

        WebhookRetry.WebhookSendResult result = DiscordNotifier.retryMultipart(
                fake, URI.create(PRIMARY), "{\"content\":\"x\"}", body,
                new WebhookRetry.RetryConfig(3, 100), delays::add, () -> 0L);

        assertTrue(result.delivered());
        assertEquals(2, result.attempts());
        assertEquals(List.of(100L), delays);
        assertEquals(2, contentTypes.size());
        assertEquals(body.contentType(), contentTypes.get(0));
    }

    @Test
    void seasonAttachmentNameIsPurelyDerivedFromRangeAndDisplayZone() {
        SeasonRange range = SeasonRange.parse("2024-01-01", "2024-01-02", ZoneOffset.UTC).range();
        assertEquals("season-20240101-20240102.html",
                DiscordMultipart.attachmentName(range, ZoneOffset.UTC));
        assertEquals("season.html", DiscordMultipart.attachmentName(null, ZoneOffset.UTC));
        assertTrue(DiscordMultipart.tooLarge(DiscordMultipart.MAX_ATTACHMENT_BYTES + 1));
    }

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        assertTrue(start >= 0, "missing marker " + startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(end > start, "missing marker " + endMarker);
        return source.substring(start, end);
    }
}
