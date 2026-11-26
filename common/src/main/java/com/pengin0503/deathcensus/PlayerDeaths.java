package com.pengin0503.deathcensus;

import java.util.List;
import java.util.Objects;

public record PlayerDeaths(
        String uuid,
        String name,
        boolean optOutPublic,
        boolean hideCoordinates,
        boolean hidePvpOpponent,
        boolean discordOptOut,
        List<DeathEntry> deaths
) {
    /** Compatibility constructor: old callers and old snapshots have no granular flags. */
    public PlayerDeaths(String uuid, String name, boolean optOutPublic, List<DeathEntry> deaths) {
        this(uuid, name, optOutPublic, false, false, false, deaths);
    }

    public PlayerDeaths {
        uuid = uuid == null ? "" : uuid;
        name = name == null ? "" : name;
        deaths = deaths == null
                ? List.of()
                : deaths.stream().filter(Objects::nonNull).toList();
    }
}
