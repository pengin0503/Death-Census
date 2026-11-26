package com.pengin0503.deathcensus;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure composer for batched realtime Discord death notifications (DESIGN 1.3.0 §2).
 *
 * <p>Version code renders each buffered death through
 * {@code ServerLang.formatForLang(..., "discord.deathcensus.death", ...)}, caps the
 * rendered list at {@code discordBatchMaxDeaths} and formats the header / more
 * suffix from the {@code discord.deathcensus.batch.*} lang keys. This class only
 * joins those pieces in chronological order and enforces the Discord content
 * limit: exactly one death is returned unchanged so normal notifications look
 * exactly as they did before batching, and truncation keeps a closing
 * {@code "…and N more"} when there is room for it.
 *
 * <p>No I/O, configuration or language lookups happen here.
 */
public final class DiscordBatch {
    /** Discord's hard content limit; version code passes this as {@code maxChars}. */
    public static final int DISCORD_CONTENT_LIMIT = 2000;

    private DiscordBatch() {
    }

    /**
     * Composes one batched message.
     *
     * @param renderedLines already localized, chronologically ordered lines to show;
     *                      version code has already applied {@code discordBatchMaxDeaths}
     * @param totalDeaths   number of deaths the buffer held (may exceed
     *                      {@code renderedLines.size()} when the cap was applied)
     * @param header        formatted {@code discord.deathcensus.batch.header}, or null
     * @param moreSuffix    formatted {@code discord.deathcensus.batch.more}, or null
     * @param maxChars      caller limit; the result is capped at
     *                      {@code min(2000, maxChars)} characters
     * @return the composed content, or {@code ""} when there is nothing to show
     */
    public static String content(List<String> renderedLines, int totalDeaths,
                                 String header, String moreSuffix, int maxChars) {
        List<String> lines = cleanLines(renderedLines);
        if (lines.isEmpty()) {
            return "";
        }
        // A single death keeps today's notification verbatim (§12 Q5), including
        // the empty header; payloadJson already enforces the 2000-char Discord cap.
        int count = Math.max(totalDeaths, lines.size());
        if (count <= 1) {
            return lines.get(0);
        }

        int limit = Math.min(DISCORD_CONTENT_LIMIT, Math.max(0, maxChars));
        if (limit == 0) {
            return "";
        }
        String head = header != null ? header : "";
        String more = moreSuffix != null ? moreSuffix : "";
        boolean hasMore = count > lines.size() && !more.isEmpty();

        StringBuilder sb = new StringBuilder();
        if (!head.isBlank()) {
            sb.append(head);
        }
        for (String line : lines) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        if (hasMore) {
            sb.append('\n').append(more);
        }
        String composed = sb.toString();
        if (composed.length() <= limit) {
            return composed;
        }
        if (hasMore && limit > more.length()) {
            // Reserve one character for a newline so the "+N more" count stays
            // readable on its own line at the very end of the truncated content.
            int bodyLimit = limit - more.length() - 1;
            if (bodyLimit > 0) {
                return composed.substring(0, bodyLimit) + "\n" + more;
            }
            return composed.substring(0, limit - more.length()) + more;
        }
        return composed.substring(0, limit);
    }

    /** Drops null and blank lines; callers never need to pre-sanitize rendered lines. */
    private static List<String> cleanLines(List<String> renderedLines) {
        if (renderedLines == null || renderedLines.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(renderedLines.size());
        for (String line : renderedLines) {
            if (line != null && !line.isBlank()) {
                lines.add(line);
            }
        }
        return lines;
    }
}
