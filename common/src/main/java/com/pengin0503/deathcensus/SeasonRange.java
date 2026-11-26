package com.pengin0503.deathcensus;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Pure parser for the {@code /dc digest season <from> <to>} period bounds
 * (DESIGN 1.3.0 §5, §12 questions 10 and 11).
 *
 * <p>Each bound is either a strict {@code yyyy-MM-dd} local date (resolved to
 * start-of-day in the supplied zone) or {@code epoch:<millis>} for an exact
 * instant. Both bounds are inclusive; {@code from >= to} is rejected. No
 * Minecraft, config or I/O dependency is used here.
 */
public record SeasonRange(long fromMillisInclusive, long toMillisInclusive) {
    /** Precise reason a pair of bounds could not be parsed. */
    public enum Failure {
        BAD_FORMAT,
        BAD_DATE,
        INVERTED
    }

    /** Parsed range or a precise failure; exactly one of the two is present. */
    public record ParseResult(SeasonRange range, Failure failure) {
        public ParseResult {
            if ((range == null) == (failure == null)) {
                throw new IllegalArgumentException("exactly one of range or failure is required");
            }
        }

        public boolean valid() {
            return range != null;
        }

        public static ParseResult ok(SeasonRange range) {
            return new ParseResult(range, null);
        }

        public static ParseResult error(Failure failure) {
            return new ParseResult(null, failure);
        }
    }

    private static final Pattern DATE_SHAPE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final String EPOCH_PREFIX = "epoch:";

    /** Parses two command words; dates use {@code zone}'s start-of-day. */
    public static ParseResult parse(String from, String to, ZoneId zone) {
        ParsedBound start = parseBound(from, zone);
        if (start.failure() != null) {
            return ParseResult.error(start.failure());
        }
        ParsedBound end = parseBound(to, zone);
        if (end.failure() != null) {
            return ParseResult.error(end.failure());
        }
        if (start.millis() >= end.millis()) {
            return ParseResult.error(Failure.INVERTED);
        }
        return ParseResult.ok(new SeasonRange(start.millis(), end.millis()));
    }

    /** Inclusive membership test used by the season digest and HTML report. */
    public boolean contains(long epochMillis) {
        return epochMillis >= fromMillisInclusive && epochMillis <= toMillisInclusive;
    }

    /** {@code yyyyMMdd-yyyyMMdd} stamp used by {@code season-<stamp>.html}. */
    public String fileNameStamp(ZoneId zone) {
        ZoneId effective = zone != null ? zone : ZoneId.systemDefault();
        return TimeFormat.formatDate(fromMillisInclusive, effective).replace("-", "")
                + "-" + TimeFormat.formatDate(toMillisInclusive, effective).replace("-", "");
    }

    private static ParsedBound parseBound(String raw, ZoneId zone) {
        if (raw == null || raw.isBlank()) {
            return ParsedBound.failed(Failure.BAD_FORMAT);
        }
        String word = raw.trim();
        if (!word.equals(raw)) {
            // Brigadier word() arguments cannot contain spaces; keep the pure
            // parser strict as well so malformed inputs are reported uniformly.
            return ParsedBound.failed(Failure.BAD_FORMAT);
        }
        if (word.startsWith(EPOCH_PREFIX)) {
            String digits = word.substring(EPOCH_PREFIX.length());
            if (digits.isEmpty()) {
                return ParsedBound.failed(Failure.BAD_FORMAT);
            }
            try {
                return ParsedBound.ok(Long.parseLong(digits));
            } catch (NumberFormatException e) {
                return ParsedBound.failed(Failure.BAD_FORMAT);
            }
        }
        if (!DATE_SHAPE.matcher(word).matches()) {
            return ParsedBound.failed(Failure.BAD_FORMAT);
        }
        try {
            LocalDate date = LocalDate.parse(word, DateTimeFormatter.ISO_LOCAL_DATE);
            ZoneId effective = zone != null ? zone : ZoneId.systemDefault();
            return ParsedBound.ok(date.atStartOfDay(effective).toInstant().toEpochMilli());
        } catch (DateTimeParseException e) {
            return ParsedBound.failed(Failure.BAD_DATE);
        } catch (DateTimeException | ArithmeticException e) {
            return ParsedBound.failed(Failure.BAD_DATE);
        }
    }

    private record ParsedBound(Long millis, Failure failure) {
        static ParsedBound ok(long millis) {
            return new ParsedBound(millis, null);
        }

        static ParsedBound failed(Failure failure) {
            return new ParsedBound(null, failure);
        }
    }
}
