package com.pengin0503.deathcensus.ui;

import java.util.Arrays;

/**
 * Localization descriptor. Adapters resolve {@link #key()} through ServerLang
 * with the viewer's language at render time; no resolved text is stored here.
 */
public record UiText(String key, Object[] args) {
    /** Key announced when a session token is missing, stale or expired. */
    public static final String SESSION_EXPIRED_KEY = "command.deathcensus.ui.session_expired";
    /** Key used when a record may not be shown to the viewer. */
    public static final String RECORD_UNAVAILABLE_KEY = "command.deathcensus.ui.record_unavailable";

    public UiText {
        key = key == null ? "" : key;
        args = args == null ? new Object[0] : Arrays.copyOf(args, args.length);
    }

    public UiText(String key) {
        this(key, new Object[0]);
    }

    public static UiText of(String key, Object... args) {
        return new UiText(key, args);
    }

    @Override
    public Object[] args() {
        return Arrays.copyOf(args, args.length);
    }
}
