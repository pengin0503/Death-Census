package com.pengin0503.deathcensus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Asynchronous Discord webhook sender. The webhook URL is a secret: it is
 * never printed to logs or chat. All sends run on a daemon single-thread
 * executor so server ticks are never blocked; failures are logged once and
 * never retried. A stop is final (M7), and a digest timestamp is only advanced
 * after a confirmed 2xx delivery (S9).
 */
public final class DiscordNotifier {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int CHECK_INTERVAL_TICKS = 72000; // ~1 hour
    private static final long DAY_MILLIS = 86400000L;

    private static ExecutorService executor;
    private static HttpClient httpClient;
    private static int tickCounter;
    /** A stop is final: no enqueue path may recreate the worker (M7). */
    private static volatile boolean stopped;
    /** While a digest is in flight the persisted period start must remain intact (S9). */
    private static volatile boolean digestDeliveryPending;

    /** Terminal outcome of one queued POST; true only for a confirmed 2xx. */
    @FunctionalInterface
    interface DeliveryCallback {
        void onDelivery(boolean delivered);
    }

    /** The one inline POST; true only for 2xx, exceptions mean non-delivery. */
    @FunctionalInterface
    interface JsonPost {
        boolean post(URI uri, String json) throws Exception;
    }

    private DiscordNotifier() {
    }

    private static synchronized ExecutorService executor() {
        if (stopped) {
            throw new RejectedExecutionException("Discord notifier is stopped");
        }
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "deathcensus-discord");
                t.setDaemon(true);
                return t;
            });
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
        // M7: the stop flag survives even the nulling of the executor below, so
        // a late tick can no longer lazily recreate a worker that posts after stop.
        stopped = true;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        tickCounter = 0;
        // A digest dropped at shutdown keeps its full period for the next start.
        digestDeliveryPending = false;
    }

    public static void resetDigestThrottler() {
        tickCounter = 0;
    }

    /** Diagnostic/test accessor: a stop is final and never undone by an enqueue. */
    static boolean isStopped() {
        return stopped;
    }

    /** The persisted timestamp never moves backwards when delivery fails (S9). */
    static long digestTimestampAfterDelivery(long previous, long periodEnd, boolean delivered) {
        return delivered ? periodEnd : previous;
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
        enqueue(uri, content, null);
    }

    private static void enqueue(URI uri, String content, @Nullable DeliveryCallback callback) {
        enqueueJson(uri, payloadJson(content), callback);
    }

    private static void enqueueJson(URI uri, String json) {
        enqueueJson(uri, json, null);
    }

    /** Every enqueue path funnels through here, so a stopped notifier drops (M7). */
    static void enqueueJson(URI uri, String json, @Nullable DeliveryCallback callback) {
        if (stopped) {
            DeathCensus.LOGGER.warn("[DeathCensus] Discord notifier is stopped; dropping a message");
            reportDelivery(callback, false);
            return;
        }
        Runnable task = () -> deliver(uri, json, callback, DiscordNotifier::postJson);
        try {
            executor().execute(task);
        } catch (RejectedExecutionException e) {
            DeathCensus.LOGGER.warn("[DeathCensus] Discord webhook queue is full; dropping a message");
            reportDelivery(callback, false);
        }
    }

    /** Runs on the worker; the callback is invoked exactly once with the outcome. */
    static void deliver(URI uri, String json, @Nullable DeliveryCallback callback, JsonPost poster) {
        boolean delivered = false;
        try {
            delivered = poster.post(uri, json);
        } catch (Exception e) {
            DeathCensus.LOGGER.warn("[DeathCensus] Discord webhook send failed: {}", e.toString());
        }
        reportDelivery(callback, delivered);
    }

    private static boolean postJson(URI uri, String json) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            DeathCensus.LOGGER.warn("[DeathCensus] Discord webhook returned HTTP {}", status);
            return false;
        }
        return true;
    }

    static void reportDelivery(@Nullable DeliveryCallback callback, boolean delivered) {
        if (callback == null) {
            return;
        }
        try {
            callback.onDelivery(delivered);
        } catch (Exception e) {
            DeathCensus.LOGGER.warn("[DeathCensus] Discord delivery callback failed ({})",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * Posts one death notification (caller has already applied the opt-out check).
     * Realtime Discord is a public projection; use
     * {@link #notifyDeath(DeathEntry, HiddenIdentities)} when a full snapshot is
     * available so hidden opponents named in another player's record are scrubbed.
     */
    public static void notifyDeath(DeathEntry entry) {
        notifyDeath(entry, HiddenIdentities.empty());
    }

    /** As above, scrubbing snapshot-wide hidden identities before formatting (S3). */
    public static void notifyDeath(DeathEntry entry, HiddenIdentities hidden) {
        if (stopped || !Config.discordNotifyDeaths()) {
            return;
        }
        URI uri = webhookUri();
        if (uri == null) {
            return;
        }
        enqueue(uri, renderDeath(entry, hidden));
    }

    /** Renders one realtime death line, never leaking a hidden identity (S3). */
    static String renderDeath(DeathEntry entry, HiddenIdentities hidden) {
        HiddenIdentities secrets = hidden == null ? HiddenIdentities.empty() : hidden;
        DeathEntry safe = secrets.scrub(entry);
        return ServerLang.formatForLang(Config.discordLanguage(), "discord.deathcensus.death",
                safe.deathMessage().isEmpty()
                        ? safe.playerName() + " died (" + safe.causeCategory() + ")"
                        : safe.deathMessage(),
                safe.gameDay() + 1,
                safe.dimension(),
                TimeFormat.formatDuration(safe.survivalTicks()));
    }

    /** Posts a milestone announcement to the configured webhook, if any. */
    public static void notifyMilestone(String content) {
        if (stopped || !Config.milestoneAnnouncements()) {
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
        if (stopped) {
            return false;
        }
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
        if (server == null || stopped) {
            return;
        }
        if (++tickCounter >= CHECK_INTERVAL_TICKS) {
            tickCounter = 0;
            checkAndSendDigest(server);
        }
    }

    public static void checkAndSendDigest(@Nullable MinecraftServer server) {
        if (server == null || stopped || !Config.discordDigestEnabled()) {
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
        if (content.isEmpty()) {
            // DESIGN §7.1: a zero-death period advances immediately; there is
            // no delivery to confirm (S9).
            data.setLastDigestEpochMillis(now);
            return;
        }
        if (digestDeliveryPending) {
            // A previous digest is in flight or failed. Keep the full period
            // intact: the delivery callback clears this flag either way.
            return;
        }
        digestDeliveryPending = true;
        long periodStart = last;
        long periodEnd = now;
        enqueue(uri, content, delivered -> {
            try {
                if (delivered && !stopped) {
                    data.setLastDigestEpochMillis(
                            digestTimestampAfterDelivery(periodStart, periodEnd, delivered));
                }
            } finally {
                digestDeliveryPending = false;
            }
        });
    }
}
