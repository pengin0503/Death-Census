package com.pengin0503.deathcensus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure logic deciding which milestone announcements a newly recorded death
 * triggers. The server-side tracker snapshots the relevant counts before
 * calling {@code CensusData.recordDeath} and supplies the counts after it.
 */
public final class Milestones {

    public enum Kind {
        SERVER_FIRST,
        TOTAL_COUNT,
        PERSONAL_FIRST,
        CATEGORY_COMPLETE;

        /** Translation key suffix used by the chat and Discord announcements. */
        public String langKey() {
            return "milestone.deathcensus." + name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A triggered milestone. {@code value} is the reached total for
     * {@link Kind#TOTAL_COUNT} and 0 for the other kinds.
     */
    public record Milestone(Kind kind, int value) {
        public Milestone {
            if (kind == null) {
                throw new IllegalArgumentException("kind must not be null");
            }
        }
    }

    private static final int TOTAL_COUNT_STEP = 100;

    private Milestones() {
    }

    /**
     * Evaluates the milestones triggered by one death.
     *
     * @param totalDeathsBefore                 deaths across all players before this death
     * @param playerDeathsBefore                this player's deaths before this death
     * @param playerDistinctCategoriesBefore    this player's distinct cause categories before this death
     * @param playerDistinctCategoriesAfter     this player's distinct cause categories after this death
     * @param newEntry                          the death being recorded (reserved; the current rules only need the counts)
     * @return triggered milestones in a stable order; never null
     */
    public static List<Milestone> evaluate(
            int totalDeathsBefore,
            int playerDeathsBefore,
            int playerDistinctCategoriesBefore,
            int playerDistinctCategoriesAfter,
            DeathEntry newEntry
    ) {
        List<Milestone> triggered = new ArrayList<>(4);

        if (totalDeathsBefore == 0) {
            triggered.add(new Milestone(Kind.SERVER_FIRST, 0));
        }

        // The death takes the total from totalDeathsBefore to totalDeathsBefore + 1.
        int nextMultiple = (totalDeathsBefore / TOTAL_COUNT_STEP + 1) * TOTAL_COUNT_STEP;
        int totalDeathsAfter = totalDeathsBefore + 1;
        if (nextMultiple >= TOTAL_COUNT_STEP
                && totalDeathsBefore < nextMultiple
                && nextMultiple <= totalDeathsAfter) {
            triggered.add(new Milestone(Kind.TOTAL_COUNT, nextMultiple));
        }

        if (playerDeathsBefore == 0) {
            triggered.add(new Milestone(Kind.PERSONAL_FIRST, 0));
        }

        int categoryCount = CauseClassifier.CATEGORIES.size();
        if (playerDistinctCategoriesBefore < categoryCount
                && playerDistinctCategoriesAfter == categoryCount) {
            triggered.add(new Milestone(Kind.CATEGORY_COMPLETE, 0));
        }

        return List.copyOf(triggered);
    }

    /** Convenience overload for callers that only have the counts. */
    public static List<Milestone> evaluate(
            int totalDeathsBefore,
            int playerDeathsBefore,
            int playerDistinctCategoriesBefore,
            int playerDistinctCategoriesAfter
    ) {
        return evaluate(totalDeathsBefore, playerDeathsBefore,
                playerDistinctCategoriesBefore, playerDistinctCategoriesAfter, null);
    }
}
