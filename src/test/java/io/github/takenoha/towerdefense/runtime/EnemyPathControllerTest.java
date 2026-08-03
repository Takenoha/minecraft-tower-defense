package io.github.takenoha.towerdefense.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.takenoha.towerdefense.domain.EnemyObstacleClassification;
import io.github.takenoha.towerdefense.domain.EnemyObstacleFacts;
import io.github.takenoha.towerdefense.domain.EnemyPathAction;
import io.github.takenoha.towerdefense.domain.EnemyRole;
import io.github.takenoha.towerdefense.domain.EnemyRolePlanner;
import org.junit.jupiter.api.Test;

final class EnemyPathControllerTest {
    @Test
    void usesTheClassifiedBreakableObstacleForDestroyers() {
        EnemyObstacleFacts facts = facts(EnemyObstacleClassification.BREAKABLE, false);

        assertEquals(
                EnemyPathAction.BREAK_OBSTACLE,
                EnemyPathController.decide(EnemyRole.DESTROYER, false, facts, 0));
    }

    @Test
    void usesTheClassifiedBuildableGapForBuilders() {
        EnemyObstacleFacts facts = facts(EnemyObstacleClassification.BUILDABLE_GAP, true);

        assertEquals(
                EnemyPathAction.BUILD_SUPPORT,
                EnemyPathController.decide(EnemyRole.BUILDER, false, facts, 0));
    }

    @Test
    void keepsProtectedObstaclesFailClosedAfterRepeatedFailures() {
        EnemyObstacleFacts facts = facts(EnemyObstacleClassification.PROTECTED, false);

        assertEquals(
                EnemyPathAction.RECOVER,
                EnemyPathController.decide(
                        EnemyRole.DESTROYER,
                        false,
                        facts,
                        EnemyRolePlanner.RECOVERY_FAILURE_THRESHOLD));
    }

    @Test
    void directPathWinsOverAStaleObstacleSnapshot() {
        EnemyObstacleFacts facts = facts(EnemyObstacleClassification.PROTECTED, false);

        assertEquals(
                EnemyPathAction.ADVANCE,
                EnemyPathController.decide(EnemyRole.BUILDER, true, facts, 0));
    }

    private static EnemyObstacleFacts facts(
            EnemyObstacleClassification classification,
            boolean supportAvailable) {
        return new EnemyObstacleFacts(
                classification,
                classification == EnemyObstacleClassification.BREAKABLE
                        ? "minecraft:stone"
                        : "minecraft:air",
                classification == EnemyObstacleClassification.BUILDABLE_GAP
                        ? "minecraft:stone"
                        : "minecraft:air",
                true,
                supportAvailable);
    }
}
