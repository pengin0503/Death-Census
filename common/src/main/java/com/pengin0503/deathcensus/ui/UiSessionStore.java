package com.pengin0503.deathcensus.ui;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Viewer-keyed session map with five-minute TTL, rotation on navigation and
 * invalidation on mutation. Version contexts can delegate to this class; the
 * dispatcher only talks to {@link UiContext}.
 */
public final class UiSessionStore {
    private final Map<UUID, UiSession> sessions = new HashMap<>();
    private final LongSupplier clock;

    public UiSessionStore() {
        this(System::currentTimeMillis);
    }

    public UiSessionStore(LongSupplier clock) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    public UiSession issue(UUID viewer, HistoryFilter filter, UiSession.RankingSelection ranking, long dataVersion) {
        if (viewer == null) {
            return null;
        }
        UiSession session = new UiSession(
                UUID.randomUUID().toString(), clock.getAsLong(), filter, ranking, dataVersion);
        sessions.put(viewer, session);
        return session;
    }

    public UiSession current(UUID viewer) {
        UiSession session = viewer == null ? null : sessions.get(viewer);
        if (session == null) {
            return null;
        }
        if (session.isExpired(clock.getAsLong())) {
            sessions.remove(viewer);
            return null;
        }
        return session;
    }

    /** Null {@code presented} starts a fresh navigation; a mismatched token yields null. */
    public UiSession rotate(
            UUID viewer,
            UiSession presented,
            HistoryFilter filter,
            UiSession.RankingSelection ranking,
            long dataVersion
    ) {
        if (viewer == null) {
            return null;
        }
        if (presented == null || presented.token().isEmpty()) {
            return issue(viewer, filter, ranking, dataVersion);
        }
        UiSession stored = current(viewer);
        if (stored == null || !stored.token().equals(presented.token())) {
            return null;
        }
        return issue(viewer, filter, ranking, dataVersion);
    }

    /** Consumes a token: subsequent use of the same token is rejected. */
    public boolean invalidate(UUID viewer, UiSession presented) {
        UiSession stored = current(viewer);
        if (stored == null || presented == null || !stored.token().equals(presented.token())) {
            return false;
        }
        sessions.remove(viewer);
        return true;
    }

    public void clear(UUID viewer) {
        sessions.remove(viewer);
    }

    public int size() {
        return sessions.size();
    }
}
