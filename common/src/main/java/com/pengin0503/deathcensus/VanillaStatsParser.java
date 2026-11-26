package com.pengin0503.deathcensus;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tolerant reader for vanilla {@code world/stats/<uuid>.json} files
 * (DESIGN 1.3.0 §9). Deliberately dependency-free: it extracts only
 * {@code stats.minecraft:custom.minecraft:deaths} and
 * {@code stats.minecraft:custom.minecraft:killed_by}, ignores everything else,
 * returns zero/empty for missing keys and {@link VanillaStats#EMPTY} for
 * malformed input. Names are never read.
 */
public final class VanillaStatsParser {
    private static final String STATS = "stats";
    private static final String CUSTOM = "minecraft:custom";
    private static final String DEATHS = "minecraft:deaths";
    private static final String KILLED_BY = "minecraft:killed_by";
    private static final String PLAYER_ID = "minecraft:player";
    private static final int MAX_DEPTH = 64;

    private VanillaStatsParser() {
    }

    public static VanillaStats parse(String json) {
        if (json == null || json.isBlank()) {
            return VanillaStats.EMPTY;
        }
        try {
            Map<String, Object> root = asMap(new Scanner(json).readDocument());
            Map<String, Object> stats = asMap(value(root, STATS));
            Map<String, Object> custom = asMap(value(stats, CUSTOM));
            int deaths = count(custom.get(DEATHS));
            Map<String, Integer> killedBy = new LinkedHashMap<>();
            Map<String, Object> rawKilledBy = asMap(custom.get(KILLED_BY));
            for (Map.Entry<String, Object> entry : rawKilledBy.entrySet()) {
                int count = count(entry.getValue());
                if (count > 0) {
                    killedBy.put(entry.getKey(), count);
                }
            }
            return new VanillaStats(deaths, killedBy);
        } catch (RuntimeException malformed) {
            return VanillaStats.EMPTY;
        }
    }

    /**
     * Maps vanilla entity ids to cause categories: {@code classify(id, false)}
     * with the single {@code minecraft:player → player} override, because the
     * vanilla stat cannot otherwise tell that the attacker was a player.
     * Several ids may collapse to one category; their counts are summed.
     */
    public static Map<String, Integer> causeCounts(VanillaStats stats) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (stats == null) {
            return Collections.unmodifiableMap(out);
        }
        for (Map.Entry<String, Integer> entry : stats.killedBy().entrySet()) {
            String id = entry.getKey();
            String category = PLAYER_ID.equals(id) ? "player" : CauseClassifier.classify(id, false);
            if (CauseClassifier.isKnownCategory(category)) {
                out.merge(category, entry.getValue(), Integer::sum);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** Highest cause count from {@link #causeCounts}; empty string when there is none. */
    public static String topCause(VanillaStats stats) {
        String best = "";
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : causeCounts(stats).entrySet()) {
            if (entry.getValue() > bestCount
                    || (entry.getValue() == bestCount && !best.isEmpty()
                        && entry.getKey().compareTo(best) < 0)) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    private static Object value(Map<String, Object> map, String key) {
        return map == null ? null : map.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static int count(Object value) {
        if (value instanceof Number number) {
            return clamp(number.longValue());
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return 0;
            }
            try {
                return clamp(new BigDecimal(trimmed).longValue());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private static int clamp(long value) {
        if (value <= 0L) {
            return 0;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    /** Minimal recursive JSON reader; never throws checked exceptions outwards. */
    private static final class Scanner {
        private final String text;
        private int pos;

        Scanner(String text) {
            this.text = text;
        }

        Object readDocument() {
            Object value = readValue(0);
            skipWhitespace();
            if (pos != text.length()) {
                throw new IllegalArgumentException("trailing JSON content");
            }
            return value;
        }

        private Object readValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("JSON nesting too deep");
            }
            skipWhitespace();
            if (pos >= text.length()) {
                throw new IllegalArgumentException("unexpected end of JSON");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> readObject(depth + 1);
                case '[' -> readArray(depth + 1);
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject(int depth) {
            pos++; // '{'
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (consume('}')) {
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                map.put(key, readValue(depth));
                skipWhitespace();
                if (consume('}')) {
                    return map;
                }
                expect(',');
            }
        }

        private java.util.List<Object> readArray(int depth) {
            pos++; // '['
            java.util.List<Object> list = new java.util.ArrayList<>();
            skipWhitespace();
            if (consume(']')) {
                return list;
            }
            while (true) {
                list.add(readValue(depth));
                skipWhitespace();
                if (consume(']')) {
                    return list;
                }
                expect(',');
            }
        }

        private String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= text.length()) {
                        throw new IllegalArgumentException("bad JSON escape");
                    }
                    char esc = text.charAt(pos++);
                    switch (esc) {
                        case '"', '\\', '/' -> sb.append(esc);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > text.length()) {
                                throw new IllegalArgumentException("bad JSON unicode escape");
                            }
                            try {
                                sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException e) {
                                throw new IllegalArgumentException("bad JSON unicode escape", e);
                            }
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("bad JSON escape");
                    }
                } else if (c < 0x20) {
                    throw new IllegalArgumentException("control character in JSON string");
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated JSON string");
        }

        private Object readNumber() {
            int start = pos;
            if (consume('-')) {
                // sign
            }
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            if (consume('.')) {
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                pos++;
                if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            if (start == pos) {
                throw new IllegalArgumentException("invalid JSON value");
            }
            return new BigDecimal(text.substring(start, pos));
        }

        private Object readLiteral(String literal, Object value) {
            if (!text.startsWith(literal, pos)) {
                throw new IllegalArgumentException("invalid JSON literal");
            }
            pos += literal.length();
            return value;
        }

        private void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    pos++;
                } else {
                    return;
                }
            }
        }

        private boolean consume(char expected) {
            if (pos < text.length() && text.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!consume(expected)) {
                throw new IllegalArgumentException("expected '" + expected + "' in JSON");
            }
        }
    }
}
