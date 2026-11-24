package com.pengin0503.deathcensus;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class TimeFormat {
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    private TimeFormat() {
    }

    public static String formatDuration(int ticks) {
        int totalSeconds = Math.max(0, ticks) / 20;
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m " + seconds + "s";
        }
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

    public static String formatDateTime(long epochMillis, ZoneId zone) {
        ZoneId effectiveZone = zone != null ? zone : ZoneId.systemDefault();
        return DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis).atZone(effectiveZone));
    }

    public static String formatDate(long epochMillis, ZoneId zone) {
        ZoneId effectiveZone = zone != null ? zone : ZoneId.systemDefault();
        return DATE_FORMATTER.format(Instant.ofEpochMilli(epochMillis).atZone(effectiveZone));
    }
}
