package com.pengin0503.deathcensus;

import java.net.URI;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.function.LongSupplier;

/**
 * Pure webhook retry engine (DESIGN 1.3.0 §1).
 *
 * <p>Retries transport exceptions, HTTP 429 and HTTP 5xx at most
 * {@link RetryConfig#maxAttempts()} times, with fixed exponential backoff
 * (base × 2 per retry, no jitter — §12 question 1). A parseable
 * {@code Retry-After} header wins over backoff and is clamped to
 * {@code [0, 60000]} ms; an unparseable one is treated as absent. Both sleeping
 * and the clock are injected so tests never sleep and need no real time.
 *
 * <p>This class performs no I/O, logging or sleeping of its own: the version
 * code supplies a {@link WebhookSender} and only runs this engine on its
 * daemon Discord worker, so backoff never blocks a server tick. A transport
 * interruption (an {@link InterruptedException}, or an already-interrupted
 * worker thread) is terminal: the flag is restored and no further POST or
 * sleep is attempted.
 */
public final class WebhookRetry {
    public static final int DEFAULT_MAX_ATTEMPTS = 4;
    public static final long DEFAULT_BASE_DELAY_MILLIS = 1000L;
    private static final long MAX_RETRY_AFTER_MILLIS = 60_000L;
    private static final WebhookRetryDecision NO_RETRY = new WebhookRetryDecision(false, 0L);

    private WebhookRetry() {
    }

    /** Bounds for the engine; config accessors supply these values in version code. */
    public record RetryConfig(int maxAttempts, long baseDelayMillis) {
        public RetryConfig {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be at least 1");
            }
            if (baseDelayMillis < 0L) {
                throw new IllegalArgumentException("baseDelayMillis must not be negative");
            }
        }

        public static RetryConfig defaults() {
            return new RetryConfig(DEFAULT_MAX_ATTEMPTS, DEFAULT_BASE_DELAY_MILLIS);
        }
    }

    /** Injected sleep; version code passes {@code Thread::sleep}. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** Terminal outcome of one enqueued webhook message. */
    public record WebhookSendResult(int attempts, int statusCode, Exception exception, boolean delivered) {
    }

    /**
     * Sends once, then retries per {@code config} while on the calling (daemon)
     * thread. Never throws: transport failures, bad headers and interruptions
     * are all returned as a terminal non-delivered result.
     */
    public static WebhookSendResult sendWithRetry(WebhookSender sender, URI uri, String json,
                                                  RetryConfig config, Sleeper sleeper, LongSupplier clock) {
        int maxAttempts = config.maxAttempts();
        int status = 0;
        Exception failure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            status = 0;
            failure = null;
            WebhookResponse response = null;
            try {
                response = sender.send(uri, json);
            } catch (Exception e) {
                failure = e;
                if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                    // Cancellation during shutdown is terminal: restore the flag,
                    // report the observed status, and never retry or sleep.
                    Thread.currentThread().interrupt();
                    return new WebhookSendResult(attempt, status, e, false);
                }
            }
            if (response != null) {
                status = response.statusCode();
                failure = null;
            }
            if (isSuccess(status)) {
                return new WebhookSendResult(attempt, status, null, true);
            }
            if (attempt >= maxAttempts) {
                return new WebhookSendResult(attempt, status, failure, false);
            }
            WebhookRetryDecision decision = failure != null
                    ? decideOnException(attempt, config)
                    : decide(response, attempt, config, clock);
            if (!decision.retry()) {
                return new WebhookSendResult(attempt, status, failure, false);
            }
            try {
                sleeper.sleep(decision.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new WebhookSendResult(attempt, status, e, false);
            } catch (RuntimeException e) {
                return new WebhookSendResult(attempt, status, e, false);
            }
        }
        return new WebhookSendResult(maxAttempts, status, failure, false);
    }

    /** Retry decision for an HTTP response; 2xx and non-retryable 4xx stop immediately. */
    public static WebhookRetryDecision decide(WebhookResponse response, int attempt,
                                              RetryConfig config, LongSupplier clock) {
        if (response == null || !isRetryableStatus(response.statusCode())) {
            return NO_RETRY;
        }
        long delay = parseRetryAfterMillis(response.retryAfterHeader(), clock.getAsLong());
        if (delay < 0L) {
            delay = backoffMillis(attempt, config);
        }
        return new WebhookRetryDecision(true, delay);
    }

    /**
     * Ordinary transport failures are retried until the attempt budget is
     * spent. {@link #sendWithRetry} short-circuits this for interruptions.
     */
    public static WebhookRetryDecision decideOnException(int attempt, RetryConfig config) {
        return new WebhookRetryDecision(true, backoffMillis(attempt, config));
    }

    /** Delay after attempt {@code attempt}, starting at {@code baseDelayMillis}. */
    static long backoffMillis(int attempt, RetryConfig config) {
        long delay = config.baseDelayMillis();
        for (int i = 1; i < attempt; i++) {
            if (delay > Long.MAX_VALUE / 2L) {
                return Long.MAX_VALUE;
            }
            delay *= 2L;
        }
        return delay;
    }

    /**
     * {@code Retry-After} delta-seconds first, then RFC 1123 HTTP-date relative
     * to the injected clock. Returns {@code -1} when absent or unparseable;
     * otherwise clamps to {@code [0, 60000]}.
     */
    static long parseRetryAfterMillis(String header, long nowMillis) {
        if (header == null || header.isBlank()) {
            return -1L;
        }
        String value = header.trim();
        try {
            long seconds = Long.parseLong(value);
            return clamp(seconds > MAX_RETRY_AFTER_MILLIS / 1000L
                    ? MAX_RETRY_AFTER_MILLIS
                    : seconds * 1000L);
        } catch (NumberFormatException notDeltaSeconds) {
            // Fall through to HTTP-date parsing.
        }
        try {
            Instant date = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value));
            return clamp(date.toEpochMilli() - nowMillis);
        } catch (DateTimeException | ArithmeticException notHttpDate) {
            return -1L;
        }
    }

    private static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }

    private static boolean isRetryableStatus(int status) {
        return status == 429 || (status >= 500 && status < 600);
    }

    private static long clamp(long millis) {
        return Math.max(0L, Math.min(millis, MAX_RETRY_AFTER_MILLIS));
    }
}
