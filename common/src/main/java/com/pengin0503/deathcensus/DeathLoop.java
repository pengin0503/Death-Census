package com.pengin0503.deathcensus;

/**
 * A derived death loop as defined by DESIGN 1.4.0 §8: a maximal run of
 * consecutive deaths of one player in one dimension, close together in space
 * and time. Nothing here is persisted — a loop exists only as long as the
 * retained records (or the transient 16-death ring) that produced it.
 *
 * @param uuid       victim identity (empty when only a label is available)
 * @param name       victim name at the time the records were captured
 * @param dimension  exact dimension id shared by every death in the run
 * @param centerX    rounded average x of the run, in whole blocks
 * @param centerZ    rounded average z of the run, in whole blocks
 * @param count      number of deaths in the run
 * @param firstEpoch epoch millis of the first death
 * @param lastEpoch  epoch millis of the last death
 * @param topCause   most frequent cause category, ties resolved lexicographically
 */
public record DeathLoop(String uuid, String name, String dimension,
                        int centerX, int centerZ, int count,
                        long firstEpoch, long lastEpoch, String topCause) {
    public DeathLoop {
        uuid = uuid == null ? "" : uuid;
        name = name == null ? "" : name;
        dimension = dimension == null ? "" : dimension;
        topCause = topCause == null ? "" : topCause;
        count = Math.max(0, count);
    }

    /**
     * Transient anti-spam identity from §8. The count is part of the key, so a
     * run that grows from three to four deaths is a distinct (new) loop; a
     * repeated detection of the same run is not announced twice.
     */
    public String noticeKey() {
        return uuid + '\u0000' + firstEpoch + '\u0000' + count;
    }

    /** Short chunk-style position label; coordinates are whole blocks only. */
    public String positionLabel() {
        return "[X=" + centerX + ", Z=" + centerZ + "]";
    }
}
