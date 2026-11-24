package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CauseClassifierTest {

    @Test
    void classifiesStandardDamageTypesAndStripsNamespace() {
        assertEquals("fall", CauseClassifier.classify("minecraft:fall", false));
        assertEquals("fall", CauseClassifier.classify("fly_into_wall", false));
        assertEquals("drown", CauseClassifier.classify("minecraft:drown", false));
        assertEquals("fire", CauseClassifier.classify("minecraft:in_fire", false));
        assertEquals("fire", CauseClassifier.classify("minecraft:on_fire", false));
        assertEquals("fire", CauseClassifier.classify("hot_floor", false));
        assertEquals("lava", CauseClassifier.classify("MINECRAFT:LAVA", false));
        assertEquals("lightning", CauseClassifier.classify("minecraft:lightning_bolt", false));
        assertEquals("freeze", CauseClassifier.classify("minecraft:freeze", false));
        assertEquals("starve", CauseClassifier.classify("minecraft:starve", false));
        assertEquals("suffocate", CauseClassifier.classify("minecraft:in_wall", false));
        assertEquals("suffocate", CauseClassifier.classify("dry_out", false));
        assertEquals("suffocate", CauseClassifier.classify("cramming", false));
        assertEquals("cactus", CauseClassifier.classify("minecraft:cactus", false));
        assertEquals("cactus", CauseClassifier.classify("minecraft:sweet_berry_bush", false));
        assertEquals("explosion", CauseClassifier.classify("minecraft:explosion", false));
        assertEquals("explosion", CauseClassifier.classify("player_explosion", false));
        assertEquals("explosion", CauseClassifier.classify("fireball", false));
        assertEquals("magic", CauseClassifier.classify("minecraft:magic", false));
        assertEquals("magic", CauseClassifier.classify("minecraft:indirect_magic", false));
        assertEquals("magic", CauseClassifier.classify("dragon_breath", false));
        assertEquals("magic", CauseClassifier.classify("wither", false));
        assertEquals("magic", CauseClassifier.classify("wither_skull", false));
        assertEquals("magic", CauseClassifier.classify("minecraft:sonic_boom", false));
        assertEquals("explosion", CauseClassifier.classify("minecraft:bad_respawn_point", false));
        assertEquals("projectile", CauseClassifier.classify("minecraft:arrow", false));
        assertEquals("projectile", CauseClassifier.classify("trident", false));
        assertEquals("projectile", CauseClassifier.classify("fireworks", false));
        assertEquals("projectile", CauseClassifier.classify("thrown", false));
        assertEquals("projectile", CauseClassifier.classify("projectile", false));
        assertEquals("mob", CauseClassifier.classify("minecraft:spit", false));
        assertEquals("mob", CauseClassifier.classify("minecraft:sting", false));
        assertEquals("mob", CauseClassifier.classify("minecraft:thorns", false));
        assertEquals("void", CauseClassifier.classify("minecraft:out_of_world", false));
        assertEquals("void", CauseClassifier.classify("void", false));
        assertEquals("void", CauseClassifier.classify("fell_out_of_world", false));
        assertEquals("generic", CauseClassifier.classify("minecraft:generic", false));
        assertEquals("generic", CauseClassifier.classify("generic_kill", false));
        assertEquals("generic", CauseClassifier.classify("kill", false));
        assertEquals("generic", CauseClassifier.classify("falling_block", false));
        assertEquals("generic", CauseClassifier.classify("falling_stalactite", false));
        assertEquals("generic", CauseClassifier.classify("stalagmite", false));
        assertEquals("projectile", CauseClassifier.classify("minecraft:mob_projectile", false));
        assertEquals("generic", CauseClassifier.classify("minecraft:falling_anvil", false));
        assertEquals("projectile", CauseClassifier.classify("minecraft:wind_charge", false));
        assertEquals("fire", CauseClassifier.classify("minecraft:campfire", false));
        assertEquals("explosion", CauseClassifier.classify("minecraft:unattributed_fireball", false));
        assertEquals("generic", CauseClassifier.classify("minecraft:outside_border", false));
    }

    @Test
    void playerAttackAndMobPlayerPromotion() {
        assertEquals("player", CauseClassifier.classify("minecraft:player_attack", true));
        assertEquals("mob", CauseClassifier.classify("minecraft:player_attack", false));
        assertEquals("player", CauseClassifier.classify("minecraft:mace_smash", true));
        assertEquals("mob", CauseClassifier.classify("minecraft:mace_smash", false));
        assertEquals("player", CauseClassifier.classify("minecraft:mob", true));
        assertEquals("mob", CauseClassifier.classify("minecraft:mob", false));
        assertEquals("player", CauseClassifier.classify("minecraft:thorns", true));
        // Explicit non-mob damage type stays specific even when attacker is a player
        assertEquals("projectile", CauseClassifier.classify("minecraft:arrow", true));
        assertEquals("fall", CauseClassifier.classify("minecraft:fall", true));
    }

    @Test
    void unknownAndNullMapToOther() {
        assertEquals("other", CauseClassifier.classify("custommod:cursed_mist", false));
        assertEquals("other", CauseClassifier.classify("custommod:cursed_mist", true));
        assertEquals("other", CauseClassifier.classify("", false));
        assertEquals("other", CauseClassifier.classify(null, false));
    }

    @Test
    void knownCategoriesMatchList() {
        assertEquals(17, CauseClassifier.CATEGORIES.size());
        for (String category : CauseClassifier.CATEGORIES) {
            assertTrue(CauseClassifier.isKnownCategory(category));
        }
        assertFalse(CauseClassifier.isKnownCategory("nonexistent"));
        assertFalse(CauseClassifier.isKnownCategory(null));
    }
}
