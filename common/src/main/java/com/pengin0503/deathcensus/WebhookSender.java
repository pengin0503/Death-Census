package com.pengin0503.deathcensus;

import java.net.URI;

/**
 * Platform transport used by {@link WebhookRetry} (DESIGN 1.3.0 §1).
 *
 * <p>The single implementation lives in version code (it owns the
 * {@code HttpClient}); tests supply an in-memory fake and never open a socket.
 */
@FunctionalInterface
public interface WebhookSender {
    WebhookResponse send(URI uri, String json) throws Exception;
}
