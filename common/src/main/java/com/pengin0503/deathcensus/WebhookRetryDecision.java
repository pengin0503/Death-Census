package com.pengin0503.deathcensus;

/**
 * One retry decision from the pure retry engine (DESIGN 1.3.0 §1).
 *
 * <p>{@code delayMillis} is meaningful only when {@code retry} is true and is
 * always in {@code [0, 60000]}. Fixed backoff (§12 question 1) keeps it
 * deterministic; no jitter is applied.
 */
public record WebhookRetryDecision(boolean retry, long delayMillis) {
}
