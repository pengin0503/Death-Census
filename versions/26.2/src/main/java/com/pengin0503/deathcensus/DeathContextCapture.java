package com.pengin0503.deathcensus;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Version-local registry/level reads for DESIGN 1.4.0 §11. Pure id shaping and
 * bounding live in common {@link DeathContext}; no NBT, display names, localization
 * or disk/network I/O happens on the death hot path.
 */
final class DeathContextCapture {
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private DeathContextCapture() {
    }

    static DeathContext capture(ServerPlayer player) {
        if (player == null || !Config.captureDeathContext()) {
            return DeathContext.EMPTY;
        }
        int maxEffects = Config.deathContextMaxEffects();
        String mainHand = itemId(player.getMainHandItem());

        List<String> armor = new ArrayList<>(DeathContext.MAX_ARMOR);
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            armor.add(itemId(player.getItemBySlot(slot)));
        }

        List<String> effects = new ArrayList<>(Math.max(0, Math.min(maxEffects, DeathContext.HARD_MAX_EFFECTS)));
        if (maxEffects > 0) {
            for (MobEffectInstance effect : player.getActiveEffects()) {
                if (effects.size() >= maxEffects) {
                    break;
                }
                String id = effect.getEffect().unwrapKey()
                        .map(key -> key.identifier().toString()).orElse("");
                if (!id.isEmpty()) {
                    effects.add(id + ":" + effect.getAmplifier());
                }
            }
        }

        String biome = player.level().getBiome(player.blockPosition()).unwrapKey()
                .map(key -> key.identifier().toString()).orElse("");
        String weather = player.level().isThundering() ? DeathContext.WEATHER_THUNDER
                : player.level().isRaining() ? DeathContext.WEATHER_RAIN
                : DeathContext.WEATHER_CLEAR;
        return DeathContext.of(mainHand, armor, effects, biome, weather, maxEffects);
    }

    private static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? "" : key.toString();
    }
}
