package com.pengin0503.deathcensus;

import java.util.List;
import java.util.Objects;

/**
 * Death-time context component (DESIGN 1.4.0 §11). Pure storage shape for
 * PR-1: no capture runs yet, so every existing record uses {@link #EMPTY}.
 *
 * <p>Only registry ids are intended here (never item NBT or custom display
 * names). Armor is helmet-to-boots and effects are {@code id:amplifier}.
 */
public record DeathContext(
        String mainHand,
        List<String> armor,
        List<String> effects,
        String biome,
        String weather) {

    /** Immutable singleton for absent or unavailable context. */
    public static final DeathContext EMPTY = new DeathContext("", List.of(), List.of(), "", "");

    public DeathContext {
        mainHand = mainHand == null ? "" : mainHand;
        armor = armor == null ? List.of() : armor.stream().filter(Objects::nonNull).toList();
        effects = effects == null ? List.of() : effects.stream().filter(Objects::nonNull).toList();
        biome = biome == null ? "" : biome;
        weather = weather == null ? "" : weather;
    }

    /** True when no context was recorded (the old-save default). */
    public boolean isEmpty() {
        return mainHand.isEmpty() && armor.isEmpty() && effects.isEmpty()
                && biome.isEmpty() && weather.isEmpty();
    }
}
