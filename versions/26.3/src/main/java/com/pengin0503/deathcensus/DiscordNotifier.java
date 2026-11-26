package com.pengin0503.deathcensus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pengin0503.deathcensus.DiscordLogic.SeasonSection;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Asynchronous Discord webhook sender. The webhook URL is a secret: it is
 * never printed to logs or chat. All sends run on a single daemon thread with
 * a bounded queue; retries and backoff happen only on that worker, never on a
 * server tick. A terminal failure is logged once (attempt count and status
 * code only) and the message is dropped; nothing escapes into game code.
 *
 * <p>A long Retry-After deliberately delays later webhook messages; the 256
 * task queue cap bounds the memory used by a send backlog.
 *
 * <p>Realtime deaths (DESIGN 1.3.0 §2) are rendered and appended to a bounded
 * buffer instead of being posted immediately; one delayed task per buffer
 * window drains it into a single batched message and sends that through the
 * same retry-enabled path. Filters and the opt-out gate are applied before a
 * death reaches the buffer. Milestones, digests and test messages bypass it.
 */
public final class DiscordNotifier {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int CHECK_INTERVAL_TICKS = 72000; // ~1 hour
    private static final long DAY_MILLIS = 86400000L;
    private static final int QUEUE_CAPACITY = 256;
    private static final int BATCH_BUFFER_CAPACITY = 256;
    private static final int EMBED_FIELD_NAME_LIMIT = 256;
    private static final int EMBED_FIELD_VALUE_LIMIT = 1024;
    private static final String SEASON_EMBED_TITLE = "Death Census — Season summary";

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

    // Realtime batch buffer: rendered lines in chronological order. Both the
    // deque and the scheduling flag are guarded by the class monitor.
    private static final ArrayDeque<String> BATCH_BUFFER = new ArrayDeque<>();
    private static boolean batchFlushScheduled;
    private static volatile ScheduledExecutorService batchScheduler;

    private DiscordNotifier() {
    }

    private static synchronized ExecutorService executor() {
        if (executor == null || executor.isShutdown()) {
            executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(QUEUE_CAPACITY), DAEMON_FACTORY, DROP_HANDLER);
        }
        return executor;
    }

    /** A single daemon timer thread; at most one flush is pending at a time. */
    private static synchronized ScheduledExecutorService batchScheduler() {
        if (batchScheduler == null || batchScheduler.isShutdown()) {
            batchScheduler = Executors.newSingleThreadScheduledExecutor(DAEMON_FACTORY);
        }
        return batchScheduler;
    }

    private static synchronized HttpClient http() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        }
        return httpClient;
    }

    public static synchronized void shutdown() {
        // Wait on no timer, send nothing: drop any buffer still inside its
        // window and log the count (§12 Q6, matching shutdownNow for sends).
        if (batchScheduler != null) {
            batchScheduler.shutdownNow();
            batchScheduler = null;
        }
        int dropped = BATCH_BUFFER.size();
        BATCH_BUFFER.clear();
        batchFlushScheduled = false;
        if (dropped > 0) {
            DeathCensus.LOGGER.warn("[DeathCensus] Discord batch dropped {} unflushed death(s) on shutdown", dropped);
        }
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
    private static URI webhookUri(WebhookChannel channel) {
        return webhookUri(channel, Config.discordWebhookUrl(), Config.discordDigestWebhookUrl());
    }

    /** Package-visible seam for tests: resolves either channel from explicit values. */
    @Nullable
    static URI webhookUri(WebhookChannel channel, String primaryConfigured, String digestConfigured) {
        String resolved = DiscordLogic.resolveWebhookUrl(channel, primaryConfigured, digestConfigured);
        if (!DiscordLogic.isValidWebhookUrl(resolved)) {
            if (channel == WebhookChannel.DIGEST
                    && !DiscordLogic.resolveWebhookUrl(digestConfigured).isEmpty()) {
                // §4 / §12 Q9: a configured but invalid digest URL disables the
                // digest channel; log only this fact, never the value itself.
                DeathCensus.LOGGER.warn("[DeathCensus] Discord digest destination is configured but invalid; "
                        + "digest sending is disabled");
            }
            return null;
        }
        try {
            return URI.create(resolved);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String payloadJson(String content) {
        return payloadObject(content).toString();
    }

    private static JsonObject payloadObject(String content) {
        String safe = content != null ? content : "";
        if (safe.length() > 2000) {
            safe = safe.substring(0, 2000);
        }
        JsonObject body = new JsonObject();
        body.addProperty("content", safe);
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray());
        body.add("allowed_mentions", allowed);
        return body;
    }

    /**
     * Season posts alone get one embed with a field per rendered section. All
     * other sends keep the original content-only {@link #payloadJson} shape.
     */
    static String seasonPayloadJson(String content, List<SeasonSection> sections) {
        JsonObject body = payloadObject(content);
        JsonObject embed = new JsonObject();
        embed.addProperty("title", SEASON_EMBED_TITLE);
        JsonArray fields = new JsonArray();
        if (sections != null) {
            for (SeasonSection section : sections) {
                if (section == null) {
                    continue;
                }
                JsonObject field = new JsonObject();
                field.addProperty("name", clamp(section.label(), EMBED_FIELD_NAME_LIMIT));
                field.addProperty("value", clamp(section.value().isEmpty() ? "-" : section.value(),
                        EMBED_FIELD_VALUE_LIMIT));
                field.addProperty("inline", false);
                fields.add(field);
            }
        }
        embed.add("fields", fields);
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        body.add("embeds", embeds);
        return body.toString();
    }

    private static String clamp(String value, int maxChars) {
        String safe = value == null ? "" : value;
        return safe.length() > maxChars ? safe.substring(0, maxChars) : safe;
    }

    private static void enqueue(URI uri, String content) {
        enqueueJson(uri, payloadJson(content));
    }

    private static void enqueueJson(URI uri, String json) {
        try {
            executor().execute(() -> sendWithRetry(HTTP_SENDER, uri, json));
        } catch (RejectedExecutionException e) {
            // Defensive: DROP_HANDLER normally discards without throwing.
            DeathCensus.LOGGER.warn("[DeathCensus] Discord webhook queue is full; dropping a message");
        }
    }

    /** Runs on the daemon Discord worker only; sleeps and logs never touch a tick thread. */
    private static void sendWithRetry(WebhookSender sender, URI uri, String json) {
        try {
            WebhookRetry.RetryConfig config = new WebhookRetry.RetryConfig(
                    Config.discordRetryMaxAttempts(), Config.discordRetryBaseDelayMillis());
            WebhookRetry.WebhookSendResult result = WebhookRetry.sendWithRetry(
                    sender, uri, json, config, Thread::sleep, System::currentTimeMillis);
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

    /** Outcome reported to the invoking admin; the actual send is asynchronous. */
    public enum SeasonPostResult {
        NO_CHANNEL,
        CONTENT_ONLY,
        ATTACHED,
        ATTACHMENT_TOO_LARGE
    }

    /** Callback used to tell the invoking admin that the attachment was not delivered. */
    @FunctionalInterface
    public interface MessageCallback {
        void onAttachmentFailure();
    }

    @FunctionalInterface
    interface MultipartSender {
        WebhookResponse send(URI uri, String body, String contentType) throws Exception;
    }

    /**
     * Posts a manual season digest with an embed and, when present and within
     * the 1 MB cap, a multipart HTML attachment. The command passes
     * {@link WebhookChannel#DIGEST}; the periodic digest state is never read
     * or written here.
     *
     * <p>Multipart I/O and retry stay on the daemon worker; a terminal
     * attachment failure falls back to a content-only post and invokes the
     * callback, which the command uses to tell the admin the local file path.
     */
    public static SeasonPostResult postSeason(WebhookChannel channel, String content,
                                              List<SeasonSection> sections, @Nullable Path html,
                                              @Nullable MessageCallback onFailure) {
        URI uri = webhookUri(channel);
        if (uri == null) {
            return SeasonPostResult.NO_CHANNEL;
        }
        String json = seasonPayloadJson(content, sections);
        if (html == null || !Files.isRegularFile(html)) {
            enqueueJson(uri, json);
            return SeasonPostResult.CONTENT_ONLY;
        }
        long bytes;
        try {
            bytes = Files.size(html);
        } catch (IOException e) {
            DeathCensus.LOGGER.warn("[DeathCensus] Season HTML attachment could not be read ({}); sending content only",
                    e.getClass().getSimpleName());
            notifyFailure(onFailure);
            enqueueJson(uri, json);
            return SeasonPostResult.ATTACHMENT_TOO_LARGE;
        }
        if (DiscordMultipart.tooLarge(bytes)) {
            DeathCensus.LOGGER.warn("[DeathCensus] Season HTML attachment is larger than {} MB; sending content only",
                    DiscordMultipart.sizeLimitLabel());
            notifyFailure(onFailure);
            enqueueJson(uri, json);
            return SeasonPostResult.ATTACHMENT_TOO_LARGE;
        }
        String fileContent;
        try {
            fileContent = Files.readString(html, StandardCharsets.UTF_8);
        } catch (IOException e) {
            DeathCensus.LOGGER.warn("[DeathCensus] Season HTML attachment could not be read ({}); sending content only",
                    e.getClass().getSimpleName());
            notifyFailure(onFailure);
            enqueueJson(uri, json);
            return SeasonPostResult.ATTACHMENT_TOO_LARGE;
        }
        DiscordMultipart.Body body = DiscordMultipart.compose(
                json, html.getFileName().toString(), fileContent);
        MultipartSender multipartSender = DiscordNotifier::sendMultipart;
        try {
            executor().execute(() -> sendMultipartWithRetry(uri, json, body, multipartSender, onFailure));
        } catch (RejectedExecutionException e) {
            DeathCensus.LOGGER.warn("[DeathCensus] Discord webhook queue is full; dropping a season attachment");
            notifyFailure(onFailure);
            enqueueJson(uri, json);
            return SeasonPostResult.CONTENT_ONLY;
        }
        return SeasonPostResult.ATTACHED;
    }

    private static void notifyFailure(@Nullable MessageCallback onFailure) {
        if (onFailure != null) {
            onFailure.onAttachmentFailure();
        }
    }

    /** The only multipart HTTP call; built from the pure {@link DiscordMultipart} body. */
    private static WebhookResponse sendMultipart(URI uri, String body, String contentType)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString());
        return new WebhookResponse(response.statusCode(),
                response.headers().firstValue("Retry-After").orElse(null));
    }

    /**
     * Runs the PR-2 retry engine over an injected multipart sender, with all
     * clocks/sleep injected. Package-visible so tests can use a fake sender and
     * never touch HttpClient; production supplies the real transport below.
     */
    static WebhookRetry.WebhookSendResult retryMultipart(
            MultipartSender sender, URI uri, String json, DiscordMultipart.Body body,
            WebhookRetry.RetryConfig config, WebhookRetry.Sleeper sleeper, LongSupplier clock) {
        WebhookSender adapter = (target, ignored) -> sender.send(target, body.body(), body.contentType());
        return WebhookRetry.sendWithRetry(adapter, uri, json, config, sleeper, clock);
    }

    /** Runs on the daemon worker: retry the multipart POST, then fall back if needed. */
    private static void sendMultipartWithRetry(URI uri, String json, DiscordMultipart.Body body,
                                               MultipartSender sender,
                                               @Nullable MessageCallback onFailure) {
        try {
            WebhookRetry.RetryConfig config = new WebhookRetry.RetryConfig(
                    Config.discordRetryMaxAttempts(), Config.discordRetryBaseDelayMillis());
            WebhookRetry.WebhookSendResult result = retryMultipart(
                    sender, uri, json, body, config, Thread::sleep, System::currentTimeMillis);
            if (!result.delivered()) {
                Exception failure = result.exception();
                String detail = failure != null ? failure.getClass().getSimpleName() : "HTTP status";
                DeathCensus.LOGGER.warn(
                        "[DeathCensus] Season HTML attachment failed after {} attempt(s) (HTTP {}): {}; "
                                + "sending content only",
                        result.attempts(), result.statusCode(), detail);
                notifyFailure(onFailure);
                enqueueJson(uri, json);
            }
        } catch (Exception e) {
            DeathCensus.LOGGER.warn("[DeathCensus] Season HTML attachment send failed ({}); sending content only",
                    e.getClass().getSimpleName());
            notifyFailure(onFailure);
            enqueueJson(uri, json);
        }
    }

    /** Renders one death line in the configured language before it is buffered (§2). */
    private static String renderDeath(DeathEntry entry) {
        return ServerLang.formatForLang(Config.discordLanguage(), "discord.deathcensus.death",
                entry.deathMessage().isEmpty()
                        ? entry.playerName() + " died (" + entry.causeCategory() + ")"
                        : entry.deathMessage(),
                entry.gameDay() + 1,
                entry.dimension(),
                TimeFormat.formatDuration(entry.survivalTicks()));
    }

    /**
     * Queues one death notification. The opt-out gate (DeathTracker) and the §3
     * dimension/PvP filters are applied before anything is buffered, so a
     * hidden death is never named in a batch. Digests, milestones and chat
     * announcements do not call this path and are never batched.
     */
    public static void notifyDeath(DeathEntry entry) {
        if (!Config.discordNotifyDeaths()) {
            return;
        }
        if (!DiscordLogic.shouldNotify(entry, Config.discordNotifyDimensions(),
                Config.discordNotifyPvpMode())) {
            return;
        }
        int windowMillis = Math.max(0, Config.discordBatchWindowMillis());
        if (windowMillis == 0) {
            // Escape hatch (§12 Q4): exactly today's one POST per death.
            URI uri = webhookUri(WebhookChannel.REALTIME);
            if (uri != null) {
                enqueue(uri, renderDeath(entry));
            }
            return;
        }

        String line = renderDeath(entry);
        boolean schedule;
        synchronized (DiscordNotifier.class) {
            if (BATCH_BUFFER.size() >= BATCH_BUFFER_CAPACITY) {
                // Bounded process-local buffer: drop and log, never grow without limit.
                DeathCensus.LOGGER.warn(
                        "[DeathCensus] Discord batch buffer is full ({} deaths); dropping a death",
                        BATCH_BUFFER_CAPACITY);
                return;
            }
            // Only the first death in an empty buffer arms the window; the rest
            // just append, and further deaths do not extend the flush deadline.
            schedule = BATCH_BUFFER.isEmpty() && !batchFlushScheduled;
            BATCH_BUFFER.addLast(line);
            if (schedule) {
                batchFlushScheduled = true;
            }
        }
        if (schedule) {
            scheduleFlush(windowMillis);
        }
    }

    private static void scheduleFlush(int windowMillis) {
        try {
            ScheduledExecutorService scheduler = batchScheduler();
            scheduler.schedule(() -> {
                // A stop clears the scheduler and the buffer; ignore a task that
                // was already due so it cannot recreate a worker after shutdown.
                ScheduledExecutorService current = batchScheduler;
                if (current == null || current.isShutdown()) {
                    return;
                }
                try {
                    executor().execute(DiscordNotifier::flushBuffer);
                } catch (RejectedExecutionException e) {
                    clearBatchFlushFlag();
                    DeathCensus.LOGGER.warn(
                            "[DeathCensus] Discord worker queue is full; dropping a batched death message");
                }
            }, windowMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            clearBatchFlushFlag();
            DeathCensus.LOGGER.warn("[DeathCensus] Discord batch scheduler is stopped; dropping a batched death message");
        }
    }

    private static void clearBatchFlushFlag() {
        synchronized (DiscordNotifier.class) {
            batchFlushScheduled = false;
        }
    }

    /** Runs on the Discord worker: drain the whole buffer into exactly one send. */
    private static void flushBuffer() {
        List<String> lines;
        synchronized (DiscordNotifier.class) {
            if (BATCH_BUFFER.isEmpty()) {
                batchFlushScheduled = false;
                return;
            }
            lines = new ArrayList<>(BATCH_BUFFER);
            BATCH_BUFFER.clear();
            batchFlushScheduled = false;
        }

        int total = lines.size();
        int cap = Math.max(1, Config.discordBatchMaxDeaths());
        List<String> shown = lines.size() > cap ? lines.subList(0, cap) : lines;
        String lang = Config.discordLanguage();
        String header = ServerLang.formatForLang(lang, "discord.deathcensus.batch.header",
                total, Math.max(0, Config.discordBatchWindowMillis()) / 1000L);
        int extra = total - shown.size();
        String more = extra > 0
                ? ServerLang.formatForLang(lang, "discord.deathcensus.batch.more", extra)
                : "";
        String content = DiscordBatch.content(shown, total, header, more, DiscordBatch.DISCORD_CONTENT_LIMIT);
        if (content.isEmpty()) {
            return;
        }
        URI uri = webhookUri(WebhookChannel.REALTIME);
        if (uri != null) {
            enqueue(uri, content);
        }
    }

    /** Posts a milestone announcement to the configured webhook, if any. */
    public static void notifyMilestone(String content) {
        if (!Config.milestoneAnnouncements()) {
            return;
        }
        URI uri = webhookUri(WebhookChannel.REALTIME);
        if (uri == null) {
            return;
        }
        enqueue(uri, content);
    }

    /** Sends a realtime test message (the default for {@code /dc discordtest}). */
    public static boolean sendTest() {
        return sendTest(WebhookChannel.REALTIME);
    }

    /** Sends a test message to {@code channel}; false means it is not configured or invalid. */
    public static boolean sendTest(WebhookChannel channel) {
        URI uri = webhookUri(channel);
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
        URI uri = webhookUri(WebhookChannel.DIGEST);
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
