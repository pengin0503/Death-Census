package com.pengin0503.deathcensus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Asynchronous Discord webhook sender. The webhook URL is a secret: it is
 * never printed to logs or chat. All sends run on a single daemon thread with
 * a bounded queue; retries and backoff happen only on that worker, never on a
 * server tick. A terminal failure is logged once (attempt count and status
 * code only) and the message is dropped; nothing escapes into game code.
 *
 * <p>A long Retry-After deliberately delays later webhook messages; the 256
 * task queue cap bounds the memory used by a send backlog.
 */
public final class DiscordNotifier {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int CHECK_INTERVAL_TICKS = 72000; // ~1 hour
    private static final long DAY_MILLIS = 86400000L;
    private static final int QUEUE_CAPACITY = 256;

    private static final ThreadFactory DAEMON_FACTORY = r -> {
        Thread t = new Thread(r, "deathcensus-discord");
        t.setDaemon(true);
        return t;
    };

    // Rejection only logs; it never runs the task on the submitting (tick) thread.
    private static final RejectedExecutionHandler DROP_HANDLER = (runnable, executor) ->
            DeathCensus.LOGGER.warn("[DeathCensus] Discord webhook queue is full; dropping a message");

    // The only real HTTP transport; the pure retry engine owns attempt policy.
    private static final WebhookSender HTTP_SENDER = (uri, json) -> {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString());
        return new WebhookResponse(response.statusCode(),
                response.headers().firstValue("Retry-After").orElse(null));
    };

    private static ExecutorService executor;
    private static HttpClient httpClient;
    private static int tickCounter;

    private DiscordNotifier() {
    }

    private static synchronized ExecutorService executor() {
        if (executor == null || executor.isShutdown()) {
            executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(QUEUE_CAPACITY), DAEMON_FACTORY, DROP_HANDLER);
        }
        return executor;
    }

    private static synchronized HttpClient http() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        }
        return httpClient;
    }

    public static synchronized void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        tickCounter = 0;
    }

    public static void resetDigestThrottler() {
        tickCounter = 0;
    }

    @Nullable
    private static URI webhookUri() {
        String resolved = DiscordLogic.resolveWebhookUrl(Config.discordWebhookUrl());
        if (!DiscordLogic.isValidWebhookUrl(resolved)) {
            return null;
        }
        try {
            return URI.create(resolved);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String payloadJson(String content) {
        String safe = content != null ? content : "";
        if (safe.length() > 2000) {
            safe = safe.substring(0, 2000);
        }
        JsonObject body = new JsonObject();
        body.addProperty("content", safe);
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray());
        body.add("allowed_mentions", allowed);
        return body.toString();
    }

    private static void enqueue(URI uri, String content) {
        String json = payloadJson(content);
        try {
            executor().execute(() -> sendWithRetry(uri, json));
        } catch (RejectedExecutionException e) {
            // Defensive: DROP_HANDLER normally discards without throwing.
            DeathCensus.LOGGER.warn("[DeathCensus] Discord webhook queue is full; dropping a message");
        }
    }

    /** Runs on the daemon Discord worker only; sleeps and logs never touch a tick thread. */
    private static void sendWithRetry(URI uri, String json) {
        try {
            WebhookRetry.RetryConfig config = new WebhookRetry.RetryConfig(
                    Config.discordRetryMaxAttempts(), Config.discordRetryBaseDelayMillis());
            WebhookRetry.WebhookSendResult result = WebhookRetry.sendWithRetry(
                    HTTP_SENDER, uri, json, config, Thread::sleep, System::currentTimeMillis);
            if (!result.delivered()) {
                Exception failure = result.exception();
                String detail = failure != null ? failure.getClass().getSimpleName() : "HTTP status";
                DeathCensus.LOGGER.warn(
                        "[DeathCensus] Discord webhook failed after {} attempt(s) (HTTP {}): {}; dropping message",
                        result.attempts(), result.statusCode(), detail);
            }
        } catch (Exception e) {
            // Last-resort guard: no config or engine error may escape into game code.
            DeathCensus.LOGGER.warn("[DeathCensus] Discord webhook send failed ({}); dropping message",
                    e.getClass().getSimpleName());
        }
    }

    /** Posts one death notification (caller has already applied the opt-out check). */
    public static void notifyDeath(DeathEntry entry) {
        if (!Config.discordNotifyDeaths()) {
            return;
        }
        URI uri = webhookUri();
        if (uri == null) {
            return;
        }
        String content = ServerLang.formatForLang(Config.discordLanguage(), "discord.deathcensus.death",
                entry.deathMessage().isEmpty()
                        ? entry.playerName() + " died (" + entry.causeCategory() + ")"
                        : entry.deathMessage(),
                entry.gameDay() + 1,
                entry.dimension(),
                TimeFormat.formatDuration(entry.survivalTicks()));
        enqueue(uri, content);
    }

    /** Posts a milestone announcement to the configured webhook, if any. */
    public static void notifyMilestone(String content) {
        if (!Config.milestoneAnnouncements()) {
            return;
        }
        URI uri = webhookUri();
        if (uri == null) {
            return;
        }
        enqueue(uri, content);
    }

    /** Sends a test message; returns true when a webhook is configured and the message was queued. */
    public static boolean sendTest() {
        URI uri = webhookUri();
        if (uri == null) {
            return false;
        }
        enqueue(uri, ServerLang.formatForLang(Config.discordLanguage(), "discord.deathcensus.test"));
        return true;
    }

    public static void checkDigestOnServerStart(@Nullable MinecraftServer server) {
        tickCounter = 0;
        checkAndSendDigest(server);
    }

    public static void tickDigest(@Nullable MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (++tickCounter >= CHECK_INTERVAL_TICKS) {
            tickCounter = 0;
            checkAndSendDigest(server);
        }
    }

    public static void checkAndSendDigest(@Nullable MinecraftServer server) {
        if (server == null || !Config.discordDigestEnabled()) {
            return;
        }
        URI uri = webhookUri();
        if (uri == null) {
            return;
        }
        CensusData data = CensusData.get(server);
        long now = System.currentTimeMillis();
        long last = data.lastDigestEpochMillis();
        if (last <= 0L) {
            data.setLastDigestEpochMillis(now);
            return;
        }
        long interval = Math.max(1, Config.discordDigestIntervalDays()) * DAY_MILLIS;
        if (now - last < interval) {
            return;
        }
        String content = DiscordLogic.digestContent(
                data.snapshot(),
                last,
                Config.displayTimeZone(),
                DiscordLogic.labelsFor(Config.discordLanguage()));
        data.setLastDigestEpochMillis(now);
        if (!content.isEmpty()) {
            enqueue(uri, content);
        }
    }
}
