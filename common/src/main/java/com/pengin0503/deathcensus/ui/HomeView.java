package com.pengin0503.deathcensus.ui;

/** Home screen model. Survival ticks are play-time based and computed version-side. */
public record HomeView(
        int totalDeaths,
        int currentSurvivalTicks,
        int serverTotalDeaths,
        String lastDeathLine,
        boolean admin
) {
    public HomeView {
        lastDeathLine = lastDeathLine == null ? "" : lastDeathLine;
    }
}
