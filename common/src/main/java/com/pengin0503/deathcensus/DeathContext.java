package com.pengin0503.deathcensus;

import java.util.ArrayList;
import java.util.List;

/**
 * Death-time context component (DESIGN 1.4.0 §11). Pure storage/shaping logic:
 * the version trees read registry ids and hand them to {@link #of}; only ids are
 * kept here (never item NBT, display names or localized text).
 *
 * <p>Armor is helmet-to-boots and effects are {@code id:amplifier}. All lists are
 * immutable and bounded; {@link #EMPTY} is the singleton for absent/unavailable
 * context (old saves, capture disabled, synthetic demo records).
 */
public record DeathContext(
        String mainHand,
        List<String> armor,
        List<String> effects,
        String biome,
        String weather) {

    /** Four equipment slots, helmet first. */
    public static final int MAX_ARMOR = 4;
    /** Default configured effect cap (§13.3). */
    public static final int DEFAULT_MAX_EFFECTS = 8;
    /** Hard ceiling even when a server raises the configured cap. */
    public static final int HARD_MAX_EFFECTS = 16;

    public static final String WEATHER_CLEAR = "clear";
    public static final String WEATHER_RAIN = "rain";
    public static final String WEATHER_THUNDER = "thunder";

    /** Immutable singleton for absent or unavailable context. */
    public static final DeathContext EMPTY = new DeathContext("", List.of(), List.of(), "", "");

    public DeathContext {
        mainHand = clean(mainHand);
        armor = bounded(armor, MAX_ARMOR);
        effects = bounded(effects, HARD_MAX_EFFECTS);
        biome = clean(biome);
        weather = cleanWeather(weather);
    }

    /**
     * Shapes raw registry ids into a bounded context. Null/blank ids are dropped
     * before the caps are applied; {@code maxEffects <= 0} stores no effects.
     * This is the only entry point the version capture helpers use.
     */
    public static DeathContext of(String mainHand, List<String> armor, List<String> effects,
                                  String biome, String weather, int maxEffects) {
        return new DeathContext(mainHand, bounded(armor, MAX_ARMOR),
                bounded(effects, clampMaxEffects(maxEffects)), biome, weather);
    }

    /** As {@link #of} with the spec default effect cap. */
    public static DeathContext of(String mainHand, List<String> armor, List<String> effects,
                                  String biome, String weather) {
        return of(mainHand, armor, effects, biome, weather, DEFAULT_MAX_EFFECTS);
    }

    /** Alias for {@link #of} for callers that prefer an explicit shaping name. */
    public static DeathContext normalize(String mainHand, List<String> armor, List<String> effects,
                                         String biome, String weather, int maxEffects) {
        return of(mainHand, armor, effects, biome, weather, maxEffects);
    }

    /** Configured cap clamped to the hard bound; zero disables effect storage. */
    public static int clampMaxEffects(int maxEffects) {
        return Math.max(0, Math.min(HARD_MAX_EFFECTS, maxEffects));
    }

    /** Registry ids are trimmed; null and blank ids become the empty string. */
    public static String clean(String id) {
        if (id == null) {
            return "";
        }
        String trimmed = id.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }

    /** Normalizes the weather word; unknown values become empty (not a guess). */
    public static String cleanWeather(String value) {
        String clean = clean(value);
        if (clean.isEmpty() || WEATHER_CLEAR.equals(clean)
                || WEATHER_RAIN.equals(clean) || WEATHER_THUNDER.equals(clean)) {
            return clean;
        }
        return "";
    }

    /** Copy with a different biome; used by render-time privacy redaction. */
    public DeathContext withBiome(String newBiome) {
        return new DeathContext(mainHand, armor, effects, newBiome, weather);
    }

    /** True when no context was recorded (the old-save default). */
    public boolean isEmpty() {
        return mainHand.isEmpty() && armor.isEmpty() && effects.isEmpty()
                && biome.isEmpty() && weather.isEmpty();
    }

    /** Immutable, null-free, blank-free copy of a bounded id list. */
    private static List<String> bounded(List<String> values, int cap) {
        if (values == null || values.isEmpty() || cap <= 0) {
            return List.of();
        }
        List<String> result = new ArrayList<>(Math.min(values.size(), cap));
        for (String value : values) {
            String clean = clean(value);
            if (!clean.isEmpty()) {
                result.add(clean);
            }
            if (result.size() >= cap) {
                break;
            }
        }
        return List.copyOf(result);
    }
}
