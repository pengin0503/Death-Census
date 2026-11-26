package com.pengin0503.deathcensus;

/**
 * The minimal outcome of one webhook HTTP attempt (DESIGN 1.3.0 §1).
 *
 * <p>Kept deliberately small and platform-free so the retry engine can be
 * tested with a fake transport. {@code retryAfterHeader} may be {@code null}
 * when Discord did not send a {@code Retry-After} header.
 */
public record WebhookResponse(int statusCode, String retryAfterHeader) {
}
