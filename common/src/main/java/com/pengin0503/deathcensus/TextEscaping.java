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
        // Plain negative numbers (e.g. coordinates) are left untouched.
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
        if (c == '=' || c == '+' || c == '@') {
            return true;
        }
        if (c == '-') {
            return i + 1 >= s.length()
                    || !(Character.isDigit(s.charAt(i + 1)) || s.charAt(i + 1) == '.');
        }
        return false;
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
