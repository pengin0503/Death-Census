package com.pengin0503.deathcensus;

import net.minecraft.commands.CommandSourceStack;

import java.util.function.Predicate;

/**
 * Version-local seam for named permission nodes (DESIGN 1.3.0 §10).
 *
 * <p>NeoForge has no built-in named permission-node API, so real LuckPerms-style
 * nodes are <strong>explicitly deferred</strong> to the later infra phase (§12
 * question 23). In 1.3.0 {@link #allowed} always returns the vanilla OP-level-2
 * fallback it is given; no config key is added (§12 question 24). Every command
 * gate and every {@code isAdmin()} decision routes through this class, so a
 * future optional-provider lookup can replace only {@link #allowed}'s body
 * without touching command or UI code.
 *
 * <p>Node labels carried by the current gates, for that future phase:
 * {@code LOOKUP} — lookup/doctor and admin visibility of other players' data;
 * {@code EXPORT} — export; {@code BACKUP} — backup/restore; {@code RESET} —
 * reset/delete; {@code DIGEST} — discordtest/reload. {@code DEV}, {@code IMPORT}
 * and {@code PRIVACY_OTHER} are reserved for the corresponding later PRs.
 */
public final class PermissionBridge {
    private PermissionBridge() {
    }

    /**
     * Resolves an access decision. The 1.3.0 body is a transparent pass-through:
     * named nodes are deferred, so the caller's vanilla OP-level-2 result wins.
     */
    public static boolean allowed(CommandSourceStack source, DeathCensusPermission node,
                                  boolean opLevelTwoFallback) {
        // §10 / §12 Q23: no built-in node API on NeoForge -> defer, return fallback.
        return opLevelTwoFallback;
    }

    /** Brigadier adapter so every {@code .requires(...)} gate routes through {@link #allowed}. */
    public static Predicate<CommandSourceStack> requires(DeathCensusPermission node,
                                                         Predicate<CommandSourceStack> opLevelTwoFallback) {
        return source -> allowed(source, node, opLevelTwoFallback.test(source));
    }
}
