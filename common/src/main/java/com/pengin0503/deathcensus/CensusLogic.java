package com.pengin0503.deathcensus;

public final class CensusLogic {
    private CensusLogic() {
    }

    public static int survivalTicks(int playTicks, int lastDeathPlayTicks, int baselinePlayTicks) {
        if (lastDeathPlayTicks >= 0) {
            return Math.max(0, playTicks - lastDeathPlayTicks);
        }
        return Math.max(0, playTicks - Math.max(0, baselinePlayTicks));
    }

    public static int excessCount(int size, int max) {
        int clampedMax = Math.max(1, max);
        return Math.max(0, size - clampedMax);
    }
}
