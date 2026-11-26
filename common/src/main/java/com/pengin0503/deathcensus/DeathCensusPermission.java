package com.pengin0503.deathcensus;

/**
 * Named permission nodes reserved for the infra phase (DESIGN 1.3.0 §10).
 *
 * <p>The node strings are the contract for a future optional permissions
 * provider; in 1.3.0 they are <em>not</em> consulted for access control. All
 * access decisions still resolve through each version's {@code PermissionBridge}
 * to the vanilla OP-level-2 result, so adding this enum changes no behavior.
 * §12 questions 23 and 24 (defer nodes; add no {@code permissionMode} config)
 * are adopted as-is.
 */
public enum DeathCensusPermission {
    EXPORT("deathcensus.export"),
    LOOKUP("deathcensus.lookup"),
    DIGEST("deathcensus.digest"),
    BACKUP("deathcensus.backup"),
    RESET("deathcensus.reset"),
    DEV("deathcensus.dev"),
    IMPORT("deathcensus.import"),
    PRIVACY_OTHER("deathcensus.privacy.other");

    private final String node;

    DeathCensusPermission(String node) {
        this.node = node;
    }

    /** The future node name; never used for access control in 1.3.0. */
    public String node() {
        return node;
    }
}
