package io.github.takenoha.towerdefense.runtime;

import io.github.takenoha.towerdefense.domain.EnemyObstacleClassification;
import io.github.takenoha.towerdefense.domain.EnemyObstacleFacts;
import java.util.Locale;
import java.util.Objects;

/**
 * Paper-independent, fail-closed classification for one candidate terrain action.
 *
 * <p>This class only consumes facts captured on the Paper main thread. It does not inspect or
 * mutate a world, and it does not authorize a role; the role gate remains in
 * {@link TerrainMutationPolicy}.</p>
 */
public final class EnemyObstacleClassifier {
    private EnemyObstacleClassifier() {
    }

    public static EnemyObstacleFacts classify(
            String currentMaterialKey,
            boolean currentReplaceable,
            boolean currentInventoryHolder,
            boolean currentCore,
            boolean currentTileState,
            String targetMaterialKey,
            boolean targetTileState,
            boolean supportAvailable,
            boolean withinCombatArea) {
        Objects.requireNonNull(currentMaterialKey, "currentMaterialKey");
        Objects.requireNonNull(targetMaterialKey, "targetMaterialKey");
        if (currentMaterialKey.isBlank()) {
            throw new IllegalArgumentException("currentMaterialKey must not be blank");
        }
        if (targetMaterialKey.isBlank()) {
            throw new IllegalArgumentException("targetMaterialKey must not be blank");
        }

        if (!withinCombatArea) {
            return facts(
                    EnemyObstacleClassification.UNAVAILABLE,
                    currentMaterialKey,
                    targetMaterialKey,
                    withinCombatArea,
                    supportAvailable);
        }

        if (currentCore
                || currentInventoryHolder
                || currentTileState
                || targetTileState
                || TerrainMutationPolicy.isRequiredMaterial(currentMaterialKey)
                || TerrainMutationPolicy.isRequiredMaterial(targetMaterialKey)) {
            return facts(
                    EnemyObstacleClassification.PROTECTED,
                    currentMaterialKey,
                    targetMaterialKey,
                    true,
                    supportAvailable);
        }

        boolean currentAir = isAir(currentMaterialKey);
        boolean targetAir = isAir(targetMaterialKey);
        if (currentAir && targetAir) {
            return facts(
                    EnemyObstacleClassification.CLEAR,
                    currentMaterialKey,
                    targetMaterialKey,
                    true,
                    supportAvailable);
        }
        if (!currentAir && targetAir) {
            return facts(
                    EnemyObstacleClassification.BREAKABLE,
                    currentMaterialKey,
                    targetMaterialKey,
                    true,
                    supportAvailable);
        }
        if (currentReplaceable && !targetAir && supportAvailable) {
            return facts(
                    EnemyObstacleClassification.BUILDABLE_GAP,
                    currentMaterialKey,
                    targetMaterialKey,
                    true,
                    true);
        }
        return facts(
                EnemyObstacleClassification.UNAVAILABLE,
                currentMaterialKey,
                targetMaterialKey,
                true,
                supportAvailable);
    }

    private static EnemyObstacleFacts facts(
            EnemyObstacleClassification classification,
            String currentMaterialKey,
            String targetMaterialKey,
            boolean withinCombatArea,
            boolean supportAvailable) {
        return new EnemyObstacleFacts(
                classification,
                currentMaterialKey,
                targetMaterialKey,
                withinCombatArea,
                supportAvailable);
    }

    private static boolean isAir(String materialKey) {
        String normalized = materialKey.toLowerCase(Locale.ROOT);
        return normalized.equals("minecraft:air")
                || normalized.equals("minecraft:cave_air")
                || normalized.equals("minecraft:void_air");
    }
}
