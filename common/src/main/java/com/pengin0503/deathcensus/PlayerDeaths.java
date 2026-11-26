package com.pengin0503.deathcensus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record PlayerDeaths(
        String uuid,
        String name,
        boolean optOutPublic,
        boolean hideCoordinates,
        boolean hidePvpOpponent,
        boolean discordOptOut,
        List<DeathEntry> deaths,
        int vanillaDeathsBaseline,
        Map<String, Integer> vanillaBaselineCauses
) {
    /** Compatibility constructor: old callers and old snapshots have no granular flags or baseline. */
    public PlayerDeaths(String uuid, String name, boolean optOutPublic, List<DeathEntry> deaths) {
        this(uuid, name, optOutPublic, false, false, false, deaths, 0, Map.of());
    }

    /** Compatibility constructor: baselines are additive metadata, old callers default to zero/empty. */
    public PlayerDeaths(String uuid, String name, boolean optOutPublic, boolean hideCoordinates,
                        boolean hidePvpOpponent, boolean discordOptOut, List<DeathEntry> deaths) {
        this(uuid, name, optOutPublic, hideCoordinates, hidePvpOpponent, discordOptOut,
                deaths, 0, Map.of());
    }

    public PlayerDeaths {
        uuid = uuid == null ? "" : uuid;
        name = name == null ? "" : name;
        deaths = deaths == null
                ? List.of()
                : deaths.stream().filter(Objects::nonNull).toList();
        Map<String, Integer> causes = new LinkedHashMap<>();
        if (vanillaBaselineCauses != null) {
            for (Map.Entry<String, Integer> entry : vanillaBaselineCauses.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0) {
                    causes.put(entry.getKey(), entry.getValue());
                }
            }
        }
        vanillaBaselineCauses = Collections.unmodifiableMap(causes);
    }
}
