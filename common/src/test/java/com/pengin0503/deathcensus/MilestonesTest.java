package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MilestonesTest {

    private static DeathEntry death(String category) {
        return new DeathEntry(
                "u1",
                "Alice",
                1_000L,
                1L,
                100,
                100,
                "minecraft:overworld",
                0.0,
                64.0,
                0.0,
                category,
                "minecraft:" + category,
                "Alice died",
                "",
                "",
                "survival",
                5,
                "",
                "",
                0
        );
    }

    private static List<Milestones.Milestone> evaluate(
            int totalBefore,
            int playerBefore,
            int categoriesBefore,
            int categoriesAfter
    ) {
        return Milestones.evaluate(totalBefore, playerBefore, categoriesBefore, categoriesAfter, death("fall"));
    }

    private static Milestones.Milestone milestone(Milestones.Kind kind, int value) {
        return new Milestones.Milestone(kind, value);
    }

    @Test
    void firstDeathTriggersServerAndPersonalFirst() {
        assertEquals(
                List.of(
                        milestone(Milestones.Kind.SERVER_FIRST, 0),
                        milestone(Milestones.Kind.PERSONAL_FIRST, 0)
                ),
                evaluate(0, 0, 0, 1)
        );
    }

    @Test
    void totalCountTriggersWhenCrossingOneHundred() {
        assertEquals(
                List.of(milestone(Milestones.Kind.TOTAL_COUNT, 100)),
                evaluate(99, 5, 3, 3)
        );
        assertEquals(
                List.of(milestone(Milestones.Kind.TOTAL_COUNT, 200)),
                evaluate(199, 7, 4, 4)
        );
    }

    @Test
    void totalCountDoesNotTriggerWhenNotCrossing() {
        assertTrue(evaluate(100, 5, 3, 3).isEmpty());
        assertTrue(evaluate(0, 0, 0, 1).stream()
                .noneMatch(m -> m.kind() == Milestones.Kind.TOTAL_COUNT));
        assertTrue(evaluate(98, 5, 3, 3).isEmpty());
    }

    @Test
    void personalFirstTriggersOnlyForPlayersFirstDeath() {
        assertEquals(
                List.of(milestone(Milestones.Kind.PERSONAL_FIRST, 0)),
                evaluate(42, 0, 2, 3)
        );
        assertTrue(evaluate(42, 1, 2, 3).isEmpty());
    }

    @Test
    void categoryCompleteTriggersAtAllCategories() {
        int categories = CauseClassifier.CATEGORIES.size();
        assertEquals(
                List.of(milestone(Milestones.Kind.CATEGORY_COMPLETE, 0)),
                evaluate(42, 3, categories - 1, categories)
        );
    }

    @Test
    void categoryCompleteDoesNotTriggerWhenAlreadyCompleteOrNotComplete() {
        int categories = CauseClassifier.CATEGORIES.size();
        assertTrue(evaluate(42, 3, categories, categories).isEmpty());
        assertTrue(evaluate(42, 3, categories - 2, categories - 1).isEmpty());
    }

    @Test
    void milestonesAreReturnedInStableOrder() {
        int categories = CauseClassifier.CATEGORIES.size();
        assertEquals(
                List.of(
                        milestone(Milestones.Kind.TOTAL_COUNT, 100),
                        milestone(Milestones.Kind.PERSONAL_FIRST, 0),
                        milestone(Milestones.Kind.CATEGORY_COMPLETE, 0)
                ),
                evaluate(99, 0, categories - 1, categories)
        );
    }

    @Test
    void noMilestonesForAnOrdinaryDeath() {
        assertTrue(evaluate(1, 1, 1, 1).isEmpty());
    }
}
