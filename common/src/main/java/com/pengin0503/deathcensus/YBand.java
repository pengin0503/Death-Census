package com.pengin0503.deathcensus;

/**
 * One anonymous altitude band for DESIGN 1.4.0 §7. It carries no player names,
 * but the band is a coarse position hint, so deaths with hidden coordinates are
 * excluded by {@link DistributionAnalysis#yBands}.
 *
 * @param minY      inclusive lower bound of the band
 * @param maxY      inclusive upper bound of the band
 * @param count     number of public-location deaths in the band
 * @param topCause  most frequent cause category in the band (alphabetical tie-break)
 */
public record YBand(int minY, int maxY, int count, String topCause) {
    public YBand {
        count = Math.max(0, count);
        topCause = topCause == null ? "" : topCause;
    }
}
