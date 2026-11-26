package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** §10 test plan: the enum must contain exactly the documented node names. */
class DeathCensusPermissionTest {
    @Test
    void containsExactlyTheDocumentedNodeNames() {
        Set<String> documented = Set.of(
                "deathcensus.export",
                "deathcensus.lookup",
                "deathcensus.digest",
                "deathcensus.backup",
                "deathcensus.reset",
                "deathcensus.dev",
                "deathcensus.import",
                "deathcensus.privacy.other");
        Set<String> actual = Arrays.stream(DeathCensusPermission.values())
                .map(DeathCensusPermission::node)
                .collect(Collectors.toSet());
        assertEquals(documented, actual);
        assertEquals(documented.size(), DeathCensusPermission.values().length,
                "no duplicate nodes");
    }
}
