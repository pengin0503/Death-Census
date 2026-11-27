package com.pengin0503.deathcensus;

/**
 * Pure formatting for the DESIGN 1.4.0 §9 respawn return guide.
 *
 * <p>Everything here is a function of coordinates, a signed height delta or a
 * tick count: no server, player, component or language API is involved, so the
 * compass/distance/height/time rules can be tested on their own. The version
 * trees turn these plain values into {@code ServerLang} components and send
 * them only to the player whose own death produced the state.
 */
public final class RespawnGuideText {
    /** §15 Q25: five minutes of vanilla despawn time at 20 ticks per second. */
    public static final int DEFAULT_MAX_TICKS = 6000;
    /** §15 Q25: stop the guide when the player is within this many blocks. */
    public static final int DEFAULT_ARRIVAL_BLOCKS = 16;
    /** §15 Q24: ActionBar refresh interval in server ticks. */
    public static final int DEFAULT_REFRESH_TICKS = 20;

    /** Eight-point compass, clockwise from north (-Z) in Minecraft coordinates. */
    private static final String[] COMPASS_POINTS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private RespawnGuideText() {
    }

    /**
     * Eight-point bearing from the player toward the death point. {@code deltaX}
     * and {@code deltaZ} are {@code death - player}; north is -Z and east is +X
     * (sectors are 45°, centred on the cardinal and intercardinal bearings).
     */
    public static String compass(double deltaX, double deltaZ) {
        if (deltaX == 0.0 && deltaZ == 0.0) {
            return COMPASS_POINTS[0]; // already at the death point
        }
        double angle = Math.toDegrees(Math.atan2(deltaX, -deltaZ));
        if (angle < 0.0) {
            angle += 360.0;
        }
        if (!Double.isFinite(angle)) {
            return COMPASS_POINTS[0];
        }
        int index = (int) Math.floor((angle + 22.5) / 45.0) % COMPASS_POINTS.length;
        return COMPASS_POINTS[index];
    }

    /** Rounded horizontal (X/Z only) distance to the death point, in blocks. */
    public static int horizontalDistance(double deltaX, double deltaZ) {
        double distance = Math.hypot(deltaX, deltaZ);
        if (!Double.isFinite(distance)) {
            return Integer.MAX_VALUE;
        }
        return clampToInt(Math.round(distance));
    }

    /**
     * Signed height difference from the player toward the death point: positive
     * means the dropped items are above the player, negative means below.
     */
    public static int heightDifference(double deathY, double playerY) {
        double difference = deathY - playerY;
        if (!Double.isFinite(difference)) {
            return 0;
        }
        return clampToInt(Math.round(difference));
    }

    /** Sign-prefixed height token; zero has no sign (language text adds the unit). */
    public static String signedHeight(int blocks) {
        return blocks > 0 ? "+" + blocks : Integer.toString(blocks);
    }

    /** Remaining despawn time rendered with the shared duration formatter. */
    public static String remainingTime(long remainingTicks) {
        return TimeFormat.formatDuration(clampToInt(Math.max(0L, remainingTicks)));
    }

    /** Arrival test uses the unrounded squared distance so edge blocks are exact. */
    public static boolean arrived(double deltaX, double deltaZ, int arrivalBlocks) {
        double radius = Math.max(0, arrivalBlocks);
        double squared = deltaX * deltaX + deltaZ * deltaZ;
        return Double.isFinite(squared) && squared <= radius * radius;
    }

    private static int clampToInt(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }
}
