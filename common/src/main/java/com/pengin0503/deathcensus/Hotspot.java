package com.pengin0503.deathcensus;

/**
 * One coarse death cell for DESIGN 1.4.0 §5. It carries only a chunk coordinate
 * (never an exact death position) and anonymous aggregates, so it is safe for
 * every public surface including anonymized exports.
 *
 * @param dimension dimension id the deaths occurred in
 * @param chunkX    {@code floorDiv(x, chunkSize)} of the cell
 * @param chunkZ    {@code floorDiv(z, chunkSize)} of the cell
 * @param count     number of public-location deaths in the cell
 * @param topCause  most frequent cause category in the cell (alphabetical tie-break)
 * @param lastEpoch most recent death epoch in the cell
 */
public record Hotspot(
        String dimension,
        int chunkX,
        int chunkZ,
        int count,
        String topCause,
        long lastEpoch
) {
    public Hotspot {
        dimension = dimension == null ? "" : dimension;
        topCause = topCause == null ? "" : topCause;
        count = Math.max(0, count);
    }

    /** Stable identity of a cell, independent of presentation order. */
    public String key() {
        return dimension + '\0' + chunkX + '\0' + chunkZ;
    }
}
