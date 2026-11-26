package com.pengin0503.deathcensus;

import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §10 test plan: with the 1.3.0 deferral body the bridge must be a transparent
 * pass-through, so admin/non-admin outcomes are identical to today's vanilla
 * OP-level-2 checks for every reserved node.
 */
class PermissionBridgeTest {
    @Test
    void allowedReturnsTheVanillaFallbackForEveryNode() {
        for (DeathCensusPermission node : DeathCensusPermission.values()) {
            assertTrue(PermissionBridge.allowed(null, node, true), node.node());
            assertFalse(PermissionBridge.allowed(null, node, false), node.node());
        }
    }

    @Test
    void requiresMirrorsAdminAndNonAdminOutcomes() {
        Predicate<CommandSourceStack> admin = source -> true;
        Predicate<CommandSourceStack> nonAdmin = source -> false;
        for (DeathCensusPermission node : DeathCensusPermission.values()) {
            assertTrue(PermissionBridge.requires(node, admin).test(null), node.node());
            assertFalse(PermissionBridge.requires(node, nonAdmin).test(null), node.node());
        }
    }
}
