package com.pengin0503.deathcensus;

import java.util.Locale;

public final class TextEscaping {
    private TextEscaping() {
    }

    public static String html(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String csvField(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        // Spreadsheet formula-injection guard: a field a spreadsheet would
        // parse as a formula gets a leading apostrophe so it stays text.
        // Plain signed numbers (e.g. coordinates) are left untouched.
        if (startsWithFormulaTrigger(s)) {
            s = "'" + s;
        }
        boolean needsQuotes = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r') {
                needsQuotes = true;
                break;
            }
        }
        if (!needsQuotes) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length() + 4);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                sb.append("\"\"");
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static boolean startsWithFormulaTrigger(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        if (i >= s.length()) {
            return false;
        }
        char c = s.charAt(i);
        if (c != '=' && c != '+' && c != '-' && c != '@') {
            return false;
        }
        // A plain number such as "-123.4" or "+5" is not a formula.
        return !isPlainNumber(trimSpacesAndTabs(s));
    }

    private static String trimSpacesAndTabs(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && (s.charAt(start) == ' ' || s.charAt(start) == '\t')) {
            start++;
        }
        while (end > start && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t')) {
            end--;
        }
        return s.substring(start, end);
    }

    // Optional leading sign, digits with at most one decimal point, at least one digit.
    private static boolean isPlainNumber(String s) {
        int i = 0;
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            i++;
        }
        boolean sawDigit = false;
        boolean sawPoint = false;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                sawDigit = true;
            } else if (c == '.' && !sawPoint) {
                sawPoint = true;
            } else {
                return false;
            }
        }
        return sawDigit;
    }

    public static String jsonString(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
