package com.pengin0503.deathcensus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the DESIGN 1.3.0 §1 retry engine. The transport is a
 * scripted fake; no socket is opened and no real sleeping occurs.
 */
class WebhookRetryTest {
    private static final URI TARGET = URI.create("https://discord.com/api/webhooks/123/abc");
    private static final String JSON = "{\"content\":\"test\"}";
    private static final long NOW = Instant.parse("2026-11-26T12:00:00Z").toEpochMilli();

    private static final class ScriptedSender implements WebhookSender {
        private final List<Object> steps = new ArrayList<>();
        private int calls;

        ScriptedSender(Object... steps) {
            this.steps.addAll(Arrays.asList(steps));
        }

        @Override
        public WebhookResponse send(URI uri, String json) throws Exception {
            if (calls >= steps.size()) {
                throw new AssertionError("unexpected extra webhook attempt");
            }
            Object step = steps.get(calls++);
            if (step instanceof Exception e) {
                throw e;
            }
            return (WebhookResponse) step;
        }
    }

    private static final class RecordingSleeper implements WebhookRetry.Sleeper {
        private final List<Long> delays = new ArrayList<>();

        @Override
        public void sleep(long millis) {
            delays.add(millis);
        }
    }

    private static WebhookRetry.WebhookSendResult send(ScriptedSender sender,
                                                       WebhookRetry.RetryConfig config,
                                                       RecordingSleeper sleeper) {
        return WebhookRetry.sendWithRetry(sender, TARGET, JSON, config, sleeper, () -> NOW);
    }

    /** Never leak an interrupt flag into the JUnit runner or a later test. */
    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void successOnFirstAttemptSleepsNothing() {
        ScriptedSender sender = new ScriptedSender(new WebhookResponse(204, null));
        RecordingSleeper sleeper = new RecordingSleeper();

        WebhookRetry.WebhookSendResult result = send(sender, WebhookRetry.RetryConfig.defaults(), sleeper);

        assertTrue(result.delivered());
        assertEquals(1, result.attempts());
        assertEquals(204, result.statusCode());
        assertNull(result.exception());
        assertEquals(List.of(), sleeper.delays);
    }

    @Test
    void retry500WithOneSecondBackoff() {
        ScriptedSender sender = new ScriptedSender(
                new WebhookResponse(500, null), new WebhookResponse(204, null));
        RecordingSleeper sleeper = new RecordingSleeper();

        WebhookRetry.WebhookSendResult result = send(sender, WebhookRetry.RetryConfig.defaults(), sleeper);

        assertTrue(result.delivered());
        assertEquals(2, result.attempts());
        assertEquals(List.of(1000L), sleeper.delays);
    }

    @Test
    void retry503OnThirdAttemptUsesTwoSecondBackoff() {
        ScriptedSender sender = new ScriptedSender(
                new WebhookResponse(500, null),
                new WebhookResponse(503, null),
                new WebhookResponse(200, null));
        RecordingSleeper sleeper = new RecordingSleeper();

        WebhookRetry.WebhookSendResult result = send(sender, WebhookRetry.RetryConfig.defaults(), sleeper);

        assertTrue(result.delivered());
        assertEquals(3, result.attempts());
        assertEquals(List.of(1000L, 2000L), sleeper.delays);
    }

    @Test
    void retry429DeltaSecondsHeaderWinsOverBackoff() {
        ScriptedSender sender = new ScriptedSender(
                new WebhookResponse(429, "5"), new WebhookResponse(204, null));
        RecordingSleeper sleeper = new RecordingSleeper();

        WebhookRetry.WebhookSendResult result = send(sender, WebhookRetry.RetryConfig.defaults(), sleeper);

        assertTrue(result.delivered());
        assertEquals(List.of(5000L), sleeper.delays);
    }

    @Test
    void retry429HttpDateIsClampedToSixtySeconds() {
        ScriptedSender sender = new ScriptedSender(
                new WebhookResponse(429, "Thu, 26 Nov 2026 13:00:00 GMT"),
                new WebhookResponse(204, null));
        RecordingSleeper sleeper = new RecordingSleeper();

        WebhookRetry.WebhookSendResult result = send(sender, WebhookRetry.RetryConfig.defaults(), sleeper);

        assertTrue(result.delivered());
        assertEquals(List.of(60_000L), sleeper.delays);
    }

    @Test
    void retry429WithoutHeaderUsesBackoff() {
        ScriptedSender sender = new ScriptedSender(
                new WebhookResponse(500, null),
                new WebhookResponse(429, null),
                new WebhookResponse(204, null));
        RecordingSleeper sleeper = new RecordingSleeper();

        WebhookRetry.WebhookSendResult result = send(sender, WebhookRetry.RetryConfig.defaults(), sleeper);

        assertTrue(result.delivered());
        assertEquals(List.of(1000L, 2000L), sleeper.delays);
    }

    @Test
    void retryAfterZeroSleepsZeroMillis() {
        ScriptedSender sender = new ScriptedSender(
                new WebhookResponse(429, "0"), new WebhookResponse(204, null));
        RecordingSleeper sleeper = new RecordingSleeper();

        WebhookRetry.WebhookSendResult result = send(sender, WebhookRetry.RetryConfig.defaults(), sleeper);

        assertTrue(result.delivered());
        assertEquals(List.of(0L), sleeper.delays);
    }

    @Test
    void pastRetryAfterDateSleepsZeroMillis() {
        ScriptedSender sender = new ScriptedSender(
                new WebhookResponse(429, "Thu, 26 Nov 2026 11:00:00 GMT"),
                new WebhookResponse(204, null));
        RecordingSleeper sleeper = new RecordingSleeper();

        WebhookRetry.WebhookSendResult result = send(sender, WebhookRetry.RetryConfig.defaults(), sleeper);

        assertTrue(result.delivered());
        assertEquals(List.of(0L), sleeper.delays);
    }

    @Test
    void unparseableRetryAfterFallsBackToBackoff() {
        ScriptedSender sender = new ScriptedSender(
                new WebhookResponse(429, "not-a-date"), new WebhookResponse(204, null));
        RecordingSleeper sleeper = new RecordingSleeper();

        WebhookRetry.WebhookSendResult result = send(sender, WebhookRetry.RetryConfig.defaults(), sleeper);

        assertTrue(result.delivered());
        assertEquals(List.of(1000L), sleeper.delays);
    }

    @Test
    void permanentFourXxAreNotRetried() {
        for (int status : new int[]{400, 401, 403, 404}) {
            ScriptedSender sender = new ScriptedSender(new WebhookResponse(status, null));
            RecordingSleeper sleeper = new RecordingSleeper();

            WebhookRetry.WebhookSendResult result = send(sender, WebhookRetry.RetryConfig.defaults(), sleeper);

            assertFalse(result.delivered(), "status " + status);
            assertEquals(1, result.attempts());
            assertEquals(status, result.statusCode());
            assertEquals(List.of(), sleeper.delays);
        }
    }

    @Test
    void transportExceptionRetriesUntilAttemptBudgetIsSpent() {
        IOException boom = new IOException("fake transport failure");
        ScriptedSender sender = new ScriptedSender(boom, boom, boom, boom);
        RecordingSleeper sleeper = new RecordingSleeper();

        WebhookRetry.WebhookSendResult result = send(sender, WebhookRetry.RetryConfig.defaults(), sleeper);

        assertFalse(result.delivered());
        assertEquals(4, result.attempts());
        assertEquals(0, result.statusCode());
        assertSame(boom, result.exception());
        assertEquals(List.of(1000L, 2000L, 4000L), sleeper.delays);
    }

    @Test
    void maxAttemptsOneDisablesRetry() {
        ScriptedSender sender = new ScriptedSender(new WebhookResponse(500, null));
        RecordingSleeper sleeper = new RecordingSleeper();

        WebhookRetry.WebhookSendResult result =
                send(sender, new WebhookRetry.RetryConfig(1, 1000L), sleeper);

        assertFalse(result.delivered());
        assertEquals(1, result.attempts());
        assertEquals(List.of(), sleeper.delays);
    }

    @Test
    void decisionsMatchRetryRules() {
        WebhookRetry.RetryConfig config = WebhookRetry.RetryConfig.defaults();

        assertEquals(new WebhookRetryDecision(true, 1000L),
                WebhookRetry.decide(new WebhookResponse(500, null), 1, config, () -> NOW));
        assertEquals(new WebhookRetryDecision(true, 4000L),
                WebhookRetry.decide(new WebhookResponse(503, null), 3, config, () -> NOW));
        assertEquals(new WebhookRetryDecision(true, 5000L),
                WebhookRetry.decide(new WebhookResponse(429, "5"), 1, config, () -> NOW));
        assertEquals(new WebhookRetryDecision(true, 2000L),
                WebhookRetry.decideOnException(2, config));
        assertEquals(new WebhookRetryDecision(false, 0L),
                WebhookRetry.decide(new WebhookResponse(400, null), 1, config, () -> NOW));
        assertEquals(new WebhookRetryDecision(false, 0L),
                WebhookRetry.decide(new WebhookResponse(404, null), 1, config, () -> NOW));
    }

    @Test
    void interruptedSleepStopsRetryingAndRestoresInterruptFlag() {
        WebhookRetry.Sleeper interrupting = millis -> {
            throw new InterruptedException("shutdown");
        };
        try {
            WebhookRetry.WebhookSendResult result = WebhookRetry.sendWithRetry(
                    new ScriptedSender(new WebhookResponse(500, null)),
                    TARGET, JSON, WebhookRetry.RetryConfig.defaults(), interrupting, () -> NOW);

            assertFalse(result.delivered());
            assertEquals(1, result.attempts());
            assertTrue(result.exception() instanceof InterruptedException);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted(); // clear for the test runner
        }
    }

    @Test
    void transportInterruptedExceptionIsTerminalWithoutRetryOrSleep() {
        InterruptedException boom = new InterruptedException("shutdownNow");
        ScriptedSender sender = new ScriptedSender(boom);
        RecordingSleeper sleeper = new RecordingSleeper();

        WebhookRetry.WebhookSendResult result = send(sender, WebhookRetry.RetryConfig.defaults(), sleeper);

        assertFalse(result.delivered());
        assertEquals(1, result.attempts());
        assertEquals(0, result.statusCode());
        assertSame(boom, result.exception());
        assertEquals(1, sender.calls);
        assertEquals(List.of(), sleeper.delays);
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void interruptedThreadWithOtherExceptionIsTerminalWithoutRetryOrSleep() {
        IOException boom = new IOException("transport failure while interrupted");
        WebhookSender sender = (uri, json) -> {
            Thread.currentThread().interrupt();
            throw boom;
        };
        RecordingSleeper sleeper = new RecordingSleeper();

        WebhookRetry.WebhookSendResult result = WebhookRetry.sendWithRetry(
                sender, TARGET, JSON, WebhookRetry.RetryConfig.defaults(), sleeper, () -> NOW);

        assertFalse(result.delivered());
        assertEquals(1, result.attempts());
        assertEquals(0, result.statusCode());
        assertSame(boom, result.exception());
        assertEquals(List.of(), sleeper.delays);
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void ordinaryIOExceptionStillRetriesAsBefore() {
        IOException boom = new IOException("fake transport failure");
        ScriptedSender sender = new ScriptedSender(boom, new WebhookResponse(204, null));
        RecordingSleeper sleeper = new RecordingSleeper();

        WebhookRetry.WebhookSendResult result = send(sender, WebhookRetry.RetryConfig.defaults(), sleeper);

        assertTrue(result.delivered());
        assertEquals(2, result.attempts());
        assertEquals(List.of(1000L), sleeper.delays);
        assertFalse(Thread.currentThread().isInterrupted());
    }
}
