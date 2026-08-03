package io.github.takenoha.towerdefense.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.EnemyObstacleClassification;
import io.github.takenoha.towerdefense.domain.EnemyObstacleFacts;
import io.github.takenoha.towerdefense.domain.EnemyPathAction;
import io.github.takenoha.towerdefense.domain.EnemyTerrainActionKind;
import org.junit.jupiter.api.Test;

final class EnemyObstacleClassifierTest {
    @Test
    void protectsStatefulAndCodeOwnedMaterialsBeforeClassifyingActions() {
        EnemyObstacleFacts inventory = classify(
                "minecraft:chest", false, true, false, false,
                "minecraft:air", false, false, true);
        EnemyObstacleFacts requiredTarget = classify(
                "minecraft:stone", false, false, false, false,
                "minecraft:oak_button", false, false, true);
        EnemyObstacleFacts tileTarget = classify(
                "minecraft:air", true, false, false, false,
                "minecraft:oak_sign", true, true, true);

        assertEquals(EnemyObstacleClassification.PROTECTED, inventory.classification());
        assertEquals(EnemyObstacleClassification.PROTECTED, requiredTarget.classification());
        assertEquals(EnemyObstacleClassification.PROTECTED, tileTarget.classification());
        assertFalse(inventory.permits(EnemyTerrainActionKind.BREAK));
        assertFalse(requiredTarget.permits(EnemyTerrainActionKind.BUILD));
        assertFalse(tileTarget.permits(EnemyTerrainActionKind.BUILD));
    }

    @Test
    void classifiesAnOrdinaryBlockBreakOnlyInsideTheCombatArea() {
        EnemyObstacleFacts breakable = classify(
                "minecraft:stone", false, false, false, false,
                "minecraft:air", false, false, true);
        EnemyObstacleFacts outside = classify(
                "minecraft:stone", false, false, false, false,
                "minecraft:air", false, false, false);

        assertEquals(EnemyObstacleClassification.BREAKABLE, breakable.classification());
        assertTrue(breakable.permits(EnemyTerrainActionKind.BREAK));
        assertEquals(EnemyObstacleClassification.UNAVAILABLE, outside.classification());
        assertFalse(outside.permits(EnemyTerrainActionKind.BREAK));
    }

    @Test
    void requiresAReplaceableGapAndVerifiedSupportForBuilding() {
        EnemyObstacleFacts buildable = classify(
                "minecraft:air", true, false, false, false,
                "minecraft:stone", false, true, true);
        EnemyObstacleFacts unsupported = classify(
                "minecraft:air", true, false, false, false,
                "minecraft:stone", false, false, true);

        assertEquals(EnemyObstacleClassification.BUILDABLE_GAP, buildable.classification());
        assertTrue(buildable.permits(EnemyTerrainActionKind.BUILD));
        assertEquals(EnemyObstacleClassification.UNAVAILABLE, unsupported.classification());
        assertFalse(unsupported.permits(EnemyTerrainActionKind.BUILD));
    }

    @Test
    void exposesSafePlannerContextsForTheLaterPathController() {
        EnemyObstacleFacts breakable = classify(
                "minecraft:stone", false, false, false, false,
                "minecraft:air", false, false, true);
        EnemyObstacleFacts protectedObstacle = classify(
                "minecraft:stone", false, false, true, false,
                "minecraft:air", false, false, true);

        assertEquals(
                EnemyPathAction.BREAK_OBSTACLE,
                io.github.takenoha.towerdefense.domain.EnemyRolePlanner.decide(
                        io.github.takenoha.towerdefense.domain.EnemyRole.DESTROYER,
                        breakable.toPathContext(0)));
        assertEquals(
                EnemyPathAction.RECALCULATE_PATH,
                io.github.takenoha.towerdefense.domain.EnemyRolePlanner.decide(
                        io.github.takenoha.towerdefense.domain.EnemyRole.DESTROYER,
                        protectedObstacle.toPathContext(0)));
    }

    private static EnemyObstacleFacts classify(
            String currentMaterialKey,
            boolean currentReplaceable,
            boolean currentInventoryHolder,
            boolean currentCore,
            boolean currentTileState,
            String targetMaterialKey,
            boolean targetTileState,
            boolean supportAvailable,
            boolean withinCombatArea) {
        return EnemyObstacleClassifier.classify(
                currentMaterialKey,
                currentReplaceable,
                currentInventoryHolder,
                currentCore,
                currentTileState,
                targetMaterialKey,
                targetTileState,
                supportAvailable,
                withinCombatArea);
    }
}
