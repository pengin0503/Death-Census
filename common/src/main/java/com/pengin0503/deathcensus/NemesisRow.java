package com.pengin0503.deathcensus;

/**
 * One row of a per-player nemesis table (DESIGN 1.4.0 §4).
 *
 * <p>{@code key} is the stable grouping key (mob type, type + custom name, or
 * rival name); {@code name} is the already privacy-composed display name and
 * {@code type} carries the killer type for named-rival rows. Rows are pure
 * derived values: nothing here is stored or tied to a Minecraft API.
 */
public record NemesisRow(NemesisAnalysis.Mode mode, String key, String type, String name, int count) {
    /** Bounds a custom mob name before it reaches chat, GUI or an export cell. */
    public static final int MAX_NAME_LENGTH = 64;

    public NemesisRow {
        mode = mode == null ? NemesisAnalysis.Mode.MOB_SPECIES : mode;
        key = key == null ? "" : key;
        type = type == null ? "" : type;
        name = name == null ? "" : (name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name);
        count = Math.max(0, count);
    }

    /** True when the display name is the shared anonymous stand-in. */
    public boolean anonymous() {
        return NemesisAnalysis.ANONYMOUS.equals(name);
    }

    /** Token used by command/GUI/export lang keys for this table. */
    public String token() {
        return mode.token();
    }
}
