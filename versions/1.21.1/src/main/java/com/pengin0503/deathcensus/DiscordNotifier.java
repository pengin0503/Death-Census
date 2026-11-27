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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Asynchronous Discord webhook sender. The webhook URL is a secret: it is
 * never printed to logs or chat. All sends run on a daemon single-thread
 * executor so server ticks are never blocked; failures are logged once and
 * never retried.
 */
public final class DiscordNotifier {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int CHECK_INTERVAL_TICKS = 72000; // ~1 hour
    private static final long DAY_MILLIS = 86400000L;

    private static ExecutorService executor;
    private static HttpClient httpClient;
    private static int tickCounter;
    /** A stop is final: no enqueue path may lazily recreate a worker (M7). */
    private static volatile boolean stopped;
    /** While a digest is in flight the persisted period start must remain intact (S9). */
    private static volatile boolean digestDeliveryPending;

    /** Terminal delivery callback: 2xx confirms, drops and failures report false. */
    @FunctionalInterface
    interface DeliveryCallback {
        void onDelivery(boolean delivered);
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
        stopped = true;
        // A digest dropped at shutdown must be retried with its full period
        // intact after a restart: its timestamp was deliberately not advanced.
        digestDeliveryPending = false;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        tickCounter = 0;
    }

    public static void resetDigestThrottler() {
        tickCounter = 0;
    }

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

    /** Enqueues one JSON post, with an optional terminal delivery callback. */
    static void enqueue(URI uri, String content, @Nullable DeliveryCallback callback) {
        String json = payloadJson(content);
        if (stopped) {
            DeathCensus.LOGGER.warn("[DeathCensus] Discord notifier is stopped; dropping a message");
            reportDelivery(callback, false);
            return;
        }
        try {
            executor().execute(() -> deliver(uri, json, callback));
        } catch (RejectedExecutionException e) {
            DeathCensus.LOGGER.warn("[DeathCensus] Discord webhook queue is full; dropping a message");
            reportDelivery(callback, false);
        }
    }

    /** Runs on the daemon worker only; a 2xx is the only confirmed delivery. */
    private static void deliver(URI uri, String json, @Nullable DeliveryCallback callback) {
        boolean delivered = false;
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                delivered = true;
            } else {
                DeathCensus.LOGGER.warn("[DeathCensus] Discord webhook returned HTTP {}", status);
            }
        } catch (Exception e) {
            DeathCensus.LOGGER.warn("[DeathCensus] Discord webhook send failed: {}", e.toString());
        }
        reportDelivery(callback, delivered);
    }

    private static void reportDelivery(@Nullable DeliveryCallback callback, boolean delivered) {
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

    /** Posts one death notification (caller has already applied the opt-out check). */
    public static void notifyDeath(DeathEntry entry) {
        notifyDeath(entry, HiddenIdentities.empty());
    }

    /**
     * As above, with the snapshot-wide dictionary of hidden identities (S3).
     * Realtime Discord is always a public viewer, so a hidden opponent must not
     * survive in the victim's free text or killer field.
     */
    public static void notifyDeath(DeathEntry entry, HiddenIdentities hidden) {
        if (!Config.discordNotifyDeaths()) {
            return;
        }
        URI uri = webhookUri();
        if (uri == null) {
            return;
        }
        enqueue(uri, renderDeath(entry, hidden));
    }

    /** Formats one death line, scrubbing hidden names before free text is rendered. */
    static String renderDeath(DeathEntry entry, HiddenIdentities hidden) {
        if (entry == null) {
            return "";
        }
        DeathEntry shown = hidden == null ? entry : hidden.scrub(entry);
        return ServerLang.formatForLang(Config.discordLanguage(), "discord.deathcensus.death",
                shown.deathMessage().isEmpty()
                        ? shown.playerName() + " died (" + shown.causeCategory() + ")"
                        : shown.deathMessage(),
                shown.gameDay() + 1,
                shown.dimension(),
                TimeFormat.formatDuration(shown.survivalTicks()));
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
        if (content.isEmpty()) {
            // DESIGN §7.1: a zero-death period advances immediately; nothing to confirm.
            data.setLastDigestEpochMillis(now);
            return;
        }
        if (digestDeliveryPending) {
            // A previous digest is in flight or failed: keep the full period
            // intact so the next check still reports those deaths (S9).
            return;
        }
        digestDeliveryPending = true;
        long periodEnd = now;
        enqueue(uri, content, delivered -> {
            try {
                if (delivered && !stopped) {
                    data.setLastDigestEpochMillis(digestTimestampAfterDelivery(last, periodEnd, delivered));
                }
            } finally {
                digestDeliveryPending = false;
            }
        });
    }
}
