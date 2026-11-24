package com.pengin0503.deathchronicle;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CauseClassifier {
    public static final List<String> CATEGORIES = List.of(
            "fall",
            "drown",
            "fire",
            "lava",
            "lightning",
            "freeze",
            "starve",
            "suffocate",
            "cactus",
            "explosion",
            "magic",
            "projectile",
            "player",
            "mob",
            "void",
            "generic",
            "other"
    );

    private static final Set<String> CATEGORY_SET = Set.copyOf(CATEGORIES);

    private CauseClassifier() {
    }

    public static String classify(String causeId, boolean attackerIsPlayer) {
        String raw = causeId == null ? "" : causeId.trim();
        int colon = raw.indexOf(':');
        String path = (colon >= 0 ? raw.substring(colon + 1) : raw).toLowerCase(Locale.ROOT);

        String result = switch (path) {
            case "fall", "fly_into_wall" -> "fall";
            case "drown" -> "drown";
            case "in_fire", "on_fire", "hot_floor" -> "fire";
            case "lava" -> "lava";
            case "lightning_bolt" -> "lightning";
            case "freeze" -> "freeze";
            case "starve" -> "starve";
            case "in_wall", "dry_out", "cramming" -> "suffocate";
            case "cactus", "sweet_berry_bush" -> "cactus";
            case "explosion", "player_explosion", "fireball" -> "explosion";
            case "magic", "indirect_magic", "dragon_breath", "wither", "wither_skull" -> "magic";
            case "arrow", "trident", "fireworks", "thrown", "projectile" -> "projectile";
            case "player_attack" -> attackerIsPlayer ? "player" : "mob";
            case "mob", "mob_attack", "mob_attack_no_aggro", "spit", "sting", "thorns" -> "mob";
            case "out_of_world", "void", "fell_out_of_world" -> "void";
            case "generic", "generic_kill", "kill", "falling_block", "falling_stalactite", "stalagmite" -> "generic";
            default -> "other";
        };

        if (attackerIsPlayer && "mob".equals(result)) {
            return "player";
        }
        return result;
    }

    public static boolean isKnownCategory(String s) {
        return s != null && CATEGORY_SET.contains(s);
    }
}
