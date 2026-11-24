package com.pengin0503.deathchronicle;

import java.util.List;
import java.util.Objects;

public record PlayerDeaths(
        String uuid,
        String name,
        boolean optOutPublic,
        List<DeathEntry> deaths
) {
    public PlayerDeaths {
        uuid = uuid == null ? "" : uuid;
        name = name == null ? "" : name;
        deaths = deaths == null
                ? List.of()
                : deaths.stream().filter(Objects::nonNull).toList();
    }
}
