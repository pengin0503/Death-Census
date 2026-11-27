package com.pengin0503.deathcensus;

/**
 * One anonymous time-of-day bucket for DESIGN 1.4.0 §7. It carries no player
 * names, so opted-out deaths can be counted here as anonymous server totals
 * under DESIGN §8.
 *
 * @param hourStart hour of the day at which this bin starts (0–23)
 * @param count     number of deaths in the bin
 * @param topCause  most frequent cause category in the bin (alphabetical tie-break)
 */
public record TimeBin(int hourStart, int count, String topCause) {
    public TimeBin {
        hourStart = Math.max(0, Math.min(23, hourStart));
        count = Math.max(0, count);
        topCause = topCause == null ? "" : topCause;
    }
}
