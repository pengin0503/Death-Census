package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Discord regressions (review S3, S9, M7); no live HTTP and no MinecraftServer needed. */
class DiscordNotifierTest {
    private static final String U1 = "00000000-0000-0000-0000-000000000001";
    private static final String U2 = "00000000-0000-0000-0000-000000000002";
    private static final URI WEBHOOK = URI.create("https://discord.com/api/webhooks/111/aaa");

    private static DeathEntry death(String message, String killer, String excuse, String lastWords) {
        return new DeathEntry(U1, "Alice", 1000L, 1L, 500, 400, "minecraft:overworld",
                1.0, 64.0, 2.0, "player", "minecraft:player", message,
                "minecraft:player", killer, "survival", 5, excuse, lastWords, 0);
    }

    private static String source(String fileName) throws IOException {
        String sourceDir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(sourceDir, "deathcensus.mainSourceDir system property is not set");
        return Files.readString(Path.of(sourceDir, "com", "pengin0503", "deathcensus", fileName));
    }

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        assertTrue(start >= 0, "missing marker " + startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(end > start, "missing marker " + endMarker);
        return source.substring(start, end);
    }

    @Test
    void digestTimestampAdvancesOnlyAfterConfirmedDelivery() throws IOException {
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
        int enqueue = digest.indexOf("enqueue(uri, content, delivered ->");
        assertTrue(enqueue > 0, "the digest must be enqueued with a delivery callback");
        int advance = digest.indexOf("setLastDigestEpochMillis(digestTimestampAfterDelivery", enqueue);
        assertTrue(advance > enqueue, "the timestamp must only advance inside the delivery callback");
    }

    @Test
    void stoppedNotifierDropsMessagesWithoutAdvancingDigestAndNeverRecreatesWorkers() throws Exception {
        Field stoppedField = DiscordNotifier.class.getDeclaredField("stopped");
        Field executorField = DiscordNotifier.class.getDeclaredField("executor");
        Field pendingField = DiscordNotifier.class.getDeclaredField("digestDeliveryPending");
        stoppedField.setAccessible(true);
        executorField.setAccessible(true);
        pendingField.setAccessible(true);
        boolean oldStopped = stoppedField.getBoolean(null);
        boolean oldPending = pendingField.getBoolean(null);
        Object oldExecutor = executorField.get(null);
        try {
            DiscordNotifier.shutdown();
            assertTrue(DiscordNotifier.isStopped());
            assertNull(executorField.get(null));

            Method executor = DiscordNotifier.class.getDeclaredMethod("executor");
            executor.setAccessible(true);
            InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                    () -> executor.invoke(null));
            assertTrue(thrown.getCause() instanceof RejectedExecutionException);
            assertNull(executorField.get(null), "a stopped notifier must not lazily create a worker");

            AtomicBoolean delivered = new AtomicBoolean(true);
            DiscordNotifier.enqueue(WEBHOOK, "digest line", delivered::set);
            assertFalse(delivered.get(), "the delivery callback must report a drop");
            assertNull(executorField.get(null), "enqueue after shutdown must not resurrect a worker");
            long previous = 5_000L;
            assertEquals(previous,
                    DiscordNotifier.digestTimestampAfterDelivery(previous, 9_000L, delivered.get()));
        } finally {
            stoppedField.setBoolean(null, oldStopped);
            pendingField.setBoolean(null, oldPending);
            executorField.set(null, oldExecutor);
        }
    }

    @Test
    void realtimeDeathLineScrubsHiddenOpponentFromFreeText() throws IOException {
        DeathEntry raw = death("Alice was slain by Bob", "Bob", "why Bob", "gg Bob");
        HiddenIdentities hidden = HiddenIdentities.of(List.of(
                new PlayerDeaths(U2, "Bob", true, List.of())));

        String publicLine = DiscordNotifier.renderDeath(raw, hidden);
        assertFalse(publicLine.contains("Bob"), publicLine);
        assertTrue(publicLine.contains("Anonymous"), publicLine);
        assertTrue(DiscordNotifier.renderDeath(raw, HiddenIdentities.empty()).contains("Bob"));

        String tracker = source("DeathTracker.java");
        assertTrue(tracker.contains("DiscordNotifier.notifyDeath(entry, HiddenIdentities.of(data.snapshot()))"),
                "realtime posts must use the full-snapshot dictionary");
    }
}
