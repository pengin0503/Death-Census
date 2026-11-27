package com.pengin0503.deathcensus;

/**
 * One derived award winner. Awards are recomputed from retained records and are
 * never persisted (DESIGN 1.4.0 §2 / §15 Q5); {@code detail} carries the optional
 * rule-specific fact (for example the rarest cause category).
 */
public record AwardResult(Awards.Rule rule, String uuid, String name, int count, String detail) {
    public AwardResult {
        if (rule == null) {
            throw new IllegalArgumentException("award rule is required");
        }
        uuid = uuid == null ? "" : uuid;
        name = name == null ? "" : name;
        detail = detail == null ? "" : detail;
        count = Math.max(0, count);
    }

    /** Lower-case lang token of the rule, e.g. {@code most_falls}. */
    public String token() {
        return rule.token();
    }

    /** Alias for {@link #count()} used by callers that render arbitrary measures. */
    public int value() {
        return count;
    }
}
