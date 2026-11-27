package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Version-side regression tests for review S3 (realtime Discord scrubbing),
 * S9 (digest timestamp only after delivery) and M7 (no worker resurrection).
 */
class DiscordNotifierTest {
    private static final String HIDDEN_UUID = "00000000-0000-0000-0000-000000000001";
    private static final String VICTIM_UUID = "00000000-0000-0000-0000-000000000002";
    private static final URI WEBHOOK = URI.create("https://example.invalid/webhook");

    private static DeathEntry entry(String message, String killerName, String excuse, String lastWords) {
        return new DeathEntry(VICTIM_UUID, "Alice", 1_000L, 3L, 900, 800,
                "minecraft:overworld", 1.0, 2.0, 3.0,
                "player", "minecraft:player", message, "minecraft:player", killerName,
                "survival", 7, excuse, lastWords, 0);
    }

    private static String source(String fileName) throws IOException {
        String sourceDir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(sourceDir, "deathcensus.mainSourceDir system property is not set");
        return Files.readString(Path.of(sourceDir, "com", "pengin0503", "deathcensus", fileName));
    }

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        assertTrue(start >= 0, "missing marker " + startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue(end >= 0, "missing end marker " + endMarker);
        return source.substring(start, end);
    }

    @Test
    void realtimeDeathContentScrubsHiddenOpponent() throws IOException {
        String stored = "Alice was slain by bob near " + HIDDEN_UUID;
        DeathEntry victim = entry(stored, "bob", "gg bob", "bye " + HIDDEN_UUID);
        PlayerDeaths hidden = new PlayerDeaths(HIDDEN_UUID, "Bob", true, List.of());
        PlayerDeaths alice = new PlayerDeaths(VICTIM_UUID, "Alice", false, List.of(victim));
        HiddenIdentities dictionary = HiddenIdentities.of(List.of(hidden, alice));

        String publicContent = DiscordNotifier.renderDeath(victim, dictionary).toLowerCase();
        assertFalse(publicContent.contains("bob"), "hidden opponent must not reach Discord");
        assertFalse(publicContent.contains(HIDDEN_UUID));
        assertTrue(publicContent.contains(HiddenIdentities.HIDDEN_MARKER.toLowerCase()));

        // Self/legacy rendering with an empty dictionary keeps the stored text.
        assertTrue(DiscordNotifier.renderDeath(victim, HiddenIdentities.empty())
                .toLowerCase().contains("bob"));
        assertEquals(stored, victim.deathMessage(), "stored records are never scrubbed");

        String notifier = source("DiscordNotifier.java");
        String legacy = between(notifier, "public static void notifyDeath(DeathEntry entry) {",
                "    /** As above, scrubbing");
        assertTrue(legacy.contains("notifyDeath(entry, HiddenIdentities.empty())"),
                "the legacy overload must delegate with an empty dictionary");
        String overload = between(notifier,
                "public static void notifyDeath(DeathEntry entry, HiddenIdentities hidden) {",
                "    /** Renders one realtime");
        assertTrue(overload.contains("renderDeath(entry, hidden)"),
                "the public overload must render through the scrubbing path");

        String tracker = source("DeathTracker.java");
        assertTrue(tracker.contains("DiscordNotifier.notifyDeath(entry, HiddenIdentities.of(data.snapshot()))"),
                "the realtime call site must pass the full-snapshot dictionary");
    }

    @Test
    void digestTimestampOnlyAdvancesOnConfirmedDelivery() throws IOException {
        long previous = 1_000L;
        long periodEnd = 2_000L;
        assertEquals(previous, DiscordNotifier.digestTimestampAfterDelivery(previous, periodEnd, false));
        assertEquals(periodEnd, DiscordNotifier.digestTimestampAfterDelivery(previous, periodEnd, true));

        String digest = between(source("DiscordNotifier.java"),
                "public static void checkAndSendDigest(", "    }\n}");
        assertTrue(digest.contains("if (content.isEmpty())"),
                "zero-death periods must advance immediately");
        assertTrue(digest.contains("data.setLastDigestEpochMillis(now)"));
        assertTrue(digest.contains("if (digestDeliveryPending)"),
                "an in-flight/failed digest must keep the full period");
        int send = digest.indexOf("enqueue(uri, content, delivered ->");
        assertTrue(send > 0, "digest must be enqueued with a delivery callback");
        int advance = digest.indexOf("digestTimestampAfterDelivery(periodStart, periodEnd, delivered)", send);
        assertTrue(advance > send, "timestamp must only advance inside the delivery callback");
    }

    @Test
    void deliveryCallbackReportsOnlyConfirmed2xx() {
        List<Boolean> outcomes = new ArrayList<>();
        DiscordNotifier.DeliveryCallback callback = outcomes::add;
        DiscordNotifier.deliver(WEBHOOK, "{}", callback, (uri, json) -> true);
        DiscordNotifier.deliver(WEBHOOK, "{}", callback, (uri, json) -> false);
        DiscordNotifier.deliver(WEBHOOK, "{}", callback, (uri, json) -> {
            throw new IOException("transport failure");
        });
        assertEquals(List.of(true, false, false), outcomes);
        assertDoesNotThrow(() -> DiscordNotifier.deliver(WEBHOOK, "{}",
                delivered -> {
                    throw new IllegalStateException("callback failure");
                }, (uri, json) -> true));
    }

    @Test
    void stoppedNotifierNeverRecreatesWorkersOrQueuesSends() throws Exception {
        Field stoppedField = DiscordNotifier.class.getDeclaredField("stopped");
        Field executorField = DiscordNotifier.class.getDeclaredField("executor");
        stoppedField.setAccessible(true);
        executorField.setAccessible(true);
        boolean wasStopped = stoppedField.getBoolean(null);
        Object oldExecutor = executorField.get(null);
        Method executor = DiscordNotifier.class.getDeclaredMethod("executor");
        executor.setAccessible(true);
        try {
            stoppedField.setBoolean(null, true);
            executorField.set(null, null);
            assertTrue(DiscordNotifier.isStopped());

            InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                    () -> executor.invoke(null));
            assertTrue(thrown.getCause() instanceof RejectedExecutionException,
                    "executor() must reject after shutdown");

            List<Boolean> outcomes = new ArrayList<>();
            DiscordNotifier.enqueueJson(WEBHOOK, "{}", outcomes::add);
            assertEquals(List.of(false), outcomes, "a stopped enqueue reports non-delivery");
            assertNull(executorField.get(null), "a stopped enqueue must not create a worker");
        } finally {
            stoppedField.setBoolean(null, wasStopped);
            executorField.set(null, oldExecutor);
        }
    }

    @Test
    void shutdownMarksTheStopFinal() throws Exception {
        Field stoppedField = DiscordNotifier.class.getDeclaredField("stopped");
        Field executorField = DiscordNotifier.class.getDeclaredField("executor");
        stoppedField.setAccessible(true);
        executorField.setAccessible(true);
        boolean wasStopped = stoppedField.getBoolean(null);
        Object oldExecutor = executorField.get(null);
        try {
            DiscordNotifier.shutdown();
            assertTrue(DiscordNotifier.isStopped(), "shutdown must latch the stopped flag");
        } finally {
            stoppedField.setBoolean(null, wasStopped);
            executorField.set(null, oldExecutor);
        }
    }
}
