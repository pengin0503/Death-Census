package com.pengin0503.deathcensus;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pengin0503.deathcensus.ui.UiSession;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PR-13 integration sweep: lang parity and cross-surface ranking-kind lists. */
class IntegrationSweepTest {
    private static final List<String> RANKING_ORDER = List.of(
            "deaths", "survival", "fastest", "rarest", "awards", "chronicle", "nemesis",
            "hotspots", "distribution_time", "distribution_y", "loops", "neardeath");

    private static JsonObject loadLang(String name) throws Exception {
        String path = "/assets/deathcensus/lang/" + name + ".json";
        try (InputStream in = IntegrationSweepTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing " + path);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    @Test
    void bundlesStayParityAtTheFinalSweepCount() throws Exception {
        JsonObject en = loadLang("en_us");
        JsonObject ja = loadLang("ja_jp");
        assertEquals(en.keySet(), ja.keySet());
        assertEquals(314, en.size());
        assertEquals(314, ja.size());
    }

    @Test
    void sweepRemovesDeadKeysAndAddsTheRespawnHelpKey() throws Exception {
        JsonObject en = loadLang("en_us");
        JsonObject ja = loadLang("ja_jp");
        for (String key : List.of("command.deathcensus.witnesses.help",
                "command.deathcensus.ui.privacy.state", "command.deathcensus.export.compare")) {
            assertFalse(en.has(key), "dead key left in en: " + key);
            assertFalse(ja.has(key), "dead key left in ja: " + key);
        }
        assertTrue(en.has("command.deathcensus.help.respawn"));
        assertTrue(ja.has("command.deathcensus.help.respawn"));
        assertFalse(en.get("command.deathcensus.help.respawn").getAsString().isBlank());
        assertFalse(ja.get("command.deathcensus.help.respawn").getAsString().isBlank());
    }

    @Test
    void chatRankingHintsListEveryRankingKindExactlyOnce() throws Exception {
        for (String name : List.of("en_us", "ja_jp")) {
            JsonObject lang = loadLang(name);
            assertEquals(new LinkedHashSet<>(RANKING_ORDER), UiSession.RankingSelection.TYPES);
            assertEquals(RANKING_ORDER, hintTypes(lang, "command.deathcensus.ui.chat.ranking_hint"), name);
            List<String> withoutAwards = new ArrayList<>(RANKING_ORDER);
            withoutAwards.remove("awards");
            assertEquals(withoutAwards,
                    hintTypes(lang, "command.deathcensus.ui.chat.ranking_hint_no_awards"), name);
        }
    }

    @Test
    void decodedWitnessListsAreBoundedToTheStorageCeiling() {
        List<WitnessRef> oversized = new ArrayList<>();
        for (int i = 0; i < DeathEntry.MAX_WITNESSES + 12; i++) {
            oversized.add(new WitnessRef("uuid-" + i, "Witness " + i));
        }
        DeathEntry entry = new DeathEntry("u", "Victim", 1L, 1L, 1, 1, "dim", 0, 0, 0,
                "fall", "minecraft:fall", "fell", "", "", "survival", 0, "", "", 0,
                oversized, DeathContext.EMPTY);
        assertEquals(DeathEntry.MAX_WITNESSES, entry.witnesses().size());
        assertEquals("uuid-0", entry.witnesses().get(0).uuid());
        assertEquals(oversized.size(), WitnessRef.clamp(oversized, 1000).size());
        assertTrue(WitnessRef.clamp(null, 0).isEmpty());
    }

    /** The hint values end in "type: a/b/c"; compare is a UI action, never a type. */
    private static List<String> hintTypes(JsonObject lang, String key) {
        String value = lang.get(key).getAsString();
        // Both languages end with "<translated label>: a/b/c" and no spaces in
        // the type list, so the final space starts it in either bundle.
        int marker = value.lastIndexOf(' ');
        assertTrue(marker >= 0, key + " has no type list: " + value);
        List<String> types = Arrays.asList(value.substring(marker + 1).trim().split("/"));
        assertFalse(types.contains("compare"), "compare is not a ranking type: " + key);
        return types;
    }
}
