package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextEscapingTest {

    @Test
    void htmlEscapesSpecialCharacters() {
        assertEquals("", TextEscaping.html(null));
        assertEquals("", TextEscaping.html(""));
        assertEquals("plain text 123", TextEscaping.html("plain text 123"));
        assertEquals(
                "&lt;a href=&quot;x&quot;&gt;A &amp; &#39;B&#39;&lt;/a&gt;",
                TextEscaping.html("<a href=\"x\">A & 'B'</a>")
        );
    }

    @Test
    void csvFieldAppliesRfc4180Quoting() {
        assertEquals("", TextEscaping.csvField(null));
        assertEquals("", TextEscaping.csvField(""));
        assertEquals("simple", TextEscaping.csvField("simple"));
        assertEquals("\"a,b\"", TextEscaping.csvField("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", TextEscaping.csvField("say \"hi\""));
        assertEquals("\"line1\nline2\"", TextEscaping.csvField("line1\nline2"));
        assertEquals("\"line1\r\nline2\"", TextEscaping.csvField("line1\r\nline2"));
    }

    @Test
    void jsonStringEscapesQuotesBackslashesAndControlChars() {
        assertEquals("", TextEscaping.jsonString(null));
        assertEquals("", TextEscaping.jsonString(""));
        assertEquals("hello", TextEscaping.jsonString("hello"));
        assertEquals("\\\"quoted\\\" \\\\ path", TextEscaping.jsonString("\"quoted\" \\ path"));
        assertEquals("a\\nb\\rc\\td", TextEscaping.jsonString("a\nb\rc\td"));
        assertEquals("\\u0000\\u0007\\u001f", TextEscaping.jsonString("\u0000\u0007\u001f"));
    }
}
