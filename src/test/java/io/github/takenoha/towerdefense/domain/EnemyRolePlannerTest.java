package io.github.takenoha.towerdefense.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EnemyRolePlannerTest {
    @Test
    void advancesEveryRoleWhenADirectPathExists() {
        EnemyPathContext context = new EnemyPathContext(true, false, false, false, 0);

        for (EnemyRole role : EnemyRole.values()) {
            assertEquals(EnemyPathAction.ADVANCE, EnemyRolePlanner.decide(role, context));
        }
    }

    @Test
    void givesDestroyersAndBuildersDifferentFallbackActions() {
        EnemyPathContext blocked = new EnemyPathContext(false, false, true, true, 0);

        assertEquals(
                EnemyPathAction.BREAK_OBSTACLE,
                EnemyRolePlanner.decide(EnemyRole.DESTROYER, blocked));
        assertEquals(
                EnemyPathAction.BUILD_SUPPORT,
                EnemyRolePlanner.decide(EnemyRole.BUILDER, blocked));
        assertEquals(
                EnemyPathAction.RECALCULATE_PATH,
                EnemyRolePlanner.decide(EnemyRole.NORMAL, blocked));
    }

    @Test
    void normalEnemiesBreakOnlyAfterTheConfiguredFallbackThreshold() {
        EnemyPathContext beforeThreshold = new EnemyPathContext(
                false, false, true, false, EnemyRolePlanner.NORMAL_BREAK_FAILURE_THRESHOLD - 1);
        EnemyPathContext atThreshold = new EnemyPathContext(
                false, false, true, false, EnemyRolePlanner.NORMAL_BREAK_FAILURE_THRESHOLD);

        assertEquals(
                EnemyPathAction.RECALCULATE_PATH,
                EnemyRolePlanner.decide(EnemyRole.NORMAL, beforeThreshold));
        assertEquals(
                EnemyPathAction.BREAK_OBSTACLE,
                EnemyRolePlanner.decide(EnemyRole.NORMAL, atThreshold));
    }

    @Test
    void protectedObstaclesNeverBecomeBreakActions() {
        EnemyPathContext protectedObstacle = new EnemyPathContext(
                false, true, true, true, EnemyRolePlanner.RECOVERY_FAILURE_THRESHOLD);

        assertEquals(
                EnemyPathAction.RECOVER,
                EnemyRolePlanner.decide(EnemyRole.DESTROYER, protectedObstacle));
    }

    @Test
    void rejectsNegativePathFailureCounts() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EnemyPathContext(false, false, false, false, -1));
    }
}
