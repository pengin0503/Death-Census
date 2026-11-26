package com.pengin0503.deathcensus.ui;

import java.util.LinkedHashMap;
import java.util.Map;

/** A button target: bare action name plus the bounded payload parameters. */
public record UiActionRef(String action, Map<String, String> params) {
    public static final String NAMESPACE = "deathcensus";

    public UiActionRef {
        action = action == null ? "" : action;
        Map<String, String> copy = new LinkedHashMap<>();
        if (params != null) {
            params.forEach((key, value) -> {
                if (key != null && value != null) {
                    copy.put(key, value);
                }
            });
        }
        params = Map.copyOf(copy);
    }

    public UiActionRef(String action) {
        this(action, Map.of());
    }

    public static UiActionRef of(String action, String key, String value) {
        return new UiActionRef(action, Map.of(key, value));
    }

    /** Full identifier used by 26.x custom click events. */
    public String identifier() {
        return NAMESPACE + ":ui/" + action;
    }
}
