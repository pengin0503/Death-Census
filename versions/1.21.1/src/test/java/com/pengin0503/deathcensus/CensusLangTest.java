package com.pengin0503.deathcensus;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every message key used in version code must exist in en_us, and ja_jp must cover the same keys. */
class CensusLangTest {
    private static JsonObject loadLang(String lang) throws IOException {
        String path = "/assets/deathcensus/lang/" + lang + ".json";
        try (InputStream in = CensusLangTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing lang resource " + path);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    @Test
    void langFilesHaveSameKeys() throws IOException {
        JsonObject en = loadLang("en_us");
        JsonObject ja = loadLang("ja_jp");
        assertEquals(en.keySet(), ja.keySet());
    }

    @Test
    void allKeysUsedInCodeExistInEnglish() throws IOException {
        String sourceDir = System.getProperty("deathcensus.mainSourceDir");
        assertNotNull(sourceDir, "deathcensus.mainSourceDir system property is not set");
        JsonObject en = loadLang("en_us");
        Pattern keyPattern = Pattern.compile("\"((?:command|discord)\\.deathcensus\\.[a-z0-9_.]+)\"");
        Set<String> used = new HashSet<>();
        try (Stream<Path> stream = Files.walk(Path.of(sourceDir))) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = keyPattern.matcher(Files.readString(file));
                while (m.find()) {
                    used.add(m.group(1));
                }
            }
        }
        assertTrue(used.size() >= 10, "expected to find message keys in sources, found " + used.size());
        for (String key : used) {
            assertTrue(en.has(key), "missing en_us entry for key " + key);
        }
    }
}
