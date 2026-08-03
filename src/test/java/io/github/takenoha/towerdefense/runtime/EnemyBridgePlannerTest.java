package io.github.takenoha.towerdefense.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.EnemyObstacleClassification;
import io.github.takenoha.towerdefense.domain.EnemyObstacleFacts;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class EnemyBridgePlannerTest {
    @Test
    void plansOneSafeMaterialWhileTheDurableActiveCountIsBelowTheCap() {
        EnemyObstacleFacts facts = facts(
                EnemyObstacleClassification.BUILDABLE_GAP,
                "minecraft:stone",
                true,
                true);

        assertEquals(
                Optional.of(new EnemyBridgePlan("minecraft:stone")),
                EnemyBridgePlanner.plan(facts, 0L));
        assertEquals(
                Optional.of(new EnemyBridgePlan("minecraft:stone")),
                EnemyBridgePlanner.plan(
                        facts,
                        EnemyBridgePlanner.MAX_ACTIVE_TEMPORARY_BLOCKS - 1L));
    }

    @Test
    void rejectsUnsafeFactsAndTheEventWideCap() {
        assertTrue(EnemyBridgePlanner.plan(
                facts(EnemyObstacleClassification.PROTECTED, "minecraft:stone", true, true),
                0L).isEmpty());
        assertTrue(EnemyBridgePlanner.plan(
                facts(EnemyObstacleClassification.BUILDABLE_GAP, "minecraft:stone", false, true),
                0L).isEmpty());
        assertTrue(EnemyBridgePlanner.plan(
                facts(EnemyObstacleClassification.BUILDABLE_GAP, "minecraft:stone", true, false),
                0L).isEmpty());
        assertTrue(EnemyBridgePlanner.plan(
                facts(EnemyObstacleClassification.BUILDABLE_GAP, "minecraft:stone", true, true),
                EnemyBridgePlanner.MAX_ACTIVE_TEMPORARY_BLOCKS).isEmpty());
        assertTrue(EnemyBridgePlanner.plan(
                facts(EnemyObstacleClassification.BUILDABLE_GAP, "minecraft:chest", true, true),
                0L).isEmpty());
        assertTrue(EnemyBridgePlanner.plan(
                facts(EnemyObstacleClassification.BUILDABLE_GAP, "minecraft:air", true, true),
                0L).isEmpty());
    }

    @Test
    void rejectsANegativeDurableCountInsteadOfWrappingTheCap() {
        assertThrows(
                IllegalArgumentException.class,
                () -> EnemyBridgePlanner.plan(
                        facts(EnemyObstacleClassification.BUILDABLE_GAP, "minecraft:stone", true, true),
                        -1L));
    }

    private static EnemyObstacleFacts facts(
            EnemyObstacleClassification classification,
            String targetMaterial,
            boolean supportAvailable,
            boolean withinCombatArea) {
        return new EnemyObstacleFacts(
                classification,
                classification == EnemyObstacleClassification.BUILDABLE_GAP
                        ? "minecraft:air"
                        : "minecraft:stone",
                targetMaterial,
                withinCombatArea,
                supportAvailable);
    }
}
