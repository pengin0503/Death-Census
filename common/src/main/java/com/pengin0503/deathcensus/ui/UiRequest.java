package com.pengin0503.deathcensus.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Normalized click input shared by both adapters. {@code action} may be either
 * a bare action name (chat command) or a full identifier (custom click event).
 * {@code deathEpoch < 0} means "no target supplied".
 */
public record UiRequest(
        UUID viewer,
        String action,
        String token,
        int page,
        String targetUuid,
        long deathEpoch,
        Map<String, String> inputs
) {
    public UiRequest {
        action = action == null ? "" : action;
        token = token == null ? "" : token;
        targetUuid = targetUuid == null ? "" : targetUuid;
        Map<String, String> copy = new LinkedHashMap<>();
        if (inputs != null) {
            inputs.forEach((key, value) -> {
                if (key != null && value != null) {
                    copy.put(key, value);
                }
            });
        }
        inputs = Map.copyOf(copy);
    }

    public UiRequest(UUID viewer, String action) {
        this(viewer, action, "", 1, "", -1L, Map.of());
    }

    public static UiRequest of(UUID viewer, String action) {
        return new UiRequest(viewer, action);
    }

    public UiRequest withPage(int newPage) {
        return new UiRequest(viewer, action, token, newPage, targetUuid, deathEpoch, inputs);
    }

    public UiRequest withTarget(UUID uuid, long epoch) {
        return new UiRequest(viewer, action, token, page, uuid == null ? "" : uuid.toString(), epoch, inputs);
    }

    public UiRequest withInputs(Map<String, String> newInputs) {
        return new UiRequest(viewer, action, token, page, targetUuid, deathEpoch, newInputs);
    }
}
