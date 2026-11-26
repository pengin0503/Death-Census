package com.pengin0503.deathcensus;

/**
 * Selects which configured Discord webhook a message is routed to (DESIGN 1.3.0 §4).
 *
 * <p>{@link #REALTIME} is the original channel used by death notifications,
 * milestones and the default {@code /dc discordtest}. {@link #DIGEST} is the
 * periodic (and reload-triggered) digest channel; it may use a separate URL.
 * Both channels share the same bounded retry engine.
 */
public enum WebhookChannel {
    REALTIME,
    DIGEST
}
