package com.pengin0503.deathcensus;

import java.util.List;

/** Read-only summary of a vanilla baseline import (or of its dry run). */
public record VanillaImportResult(List<Row> rows, int skippedPlayers, long addedDeaths) {
    /** One known player with a positive pre-install baseline. */
    public record Row(String uuid, String name, int vanillaDeaths, int baselineDeaths, String topCause) {
    }

    public VanillaImportResult {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
