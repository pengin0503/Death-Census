package com.pengin0503.deathcensus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The two vanilla statistics this mod imports (DESIGN 1.3.0 §9): the built-in
 * {@code deaths} counter and the {@code killed_by} entity-id → count map.
 * No player name, timestamp, coordinate or cause text is ever represented here.
 */
public record VanillaStats(int deaths, Map<String, Integer> killedBy) {
    public static final VanillaStats EMPTY = new VanillaStats(0, Map.of());

    public VanillaStats {
        deaths = Math.max(0, deaths);
        killedBy = killedBy == null || killedBy.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(killedBy));
    }

    public boolean isEmpty() {
        return deaths == 0 && killedBy.isEmpty();
    }
}
