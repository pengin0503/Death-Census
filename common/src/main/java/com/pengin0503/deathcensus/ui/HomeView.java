package com.pengin0503.deathcensus.ui;

/** Home screen model. Survival ticks are play-time based and computed version-side. */
public record HomeView(
        int totalDeaths,
        int currentSurvivalTicks,
        int serverTotalDeaths,
        String lastDeathLine,
        boolean admin,
        int nearDeathSaves
) {
    /** 1.3.0 shape; near-death saves are an additive, separate counter (§10). */
    public HomeView(int totalDeaths, int currentSurvivalTicks, int serverTotalDeaths,
                    String lastDeathLine, boolean admin) {
        this(totalDeaths, currentSurvivalTicks, serverTotalDeaths, lastDeathLine, admin, 0);
    }

    public HomeView {
        lastDeathLine = lastDeathLine == null ? "" : lastDeathLine;
    }
}
