package io.github.takenoha.towerdefense.domain;

import java.util.Objects;

/**
 * Paper-independent result of a main-thread world snapshot.
 *
 * <p>The result is intentionally more conservative than a pathfinder. An unavailable or
 * ambiguous snapshot never becomes a terrain action, while a later path controller may convert
 * the result into the existing role-aware path context.</p>
 */
public record EnemyObstacleFacts(
        EnemyObstacleClassification classification,
        String currentMaterialKey,
        String targetMaterialKey,
        boolean withinCombatArea,
        boolean supportAvailable) {
    public EnemyObstacleFacts {
        Objects.requireNonNull(classification, "classification");
        currentMaterialKey = requireMaterialKey(currentMaterialKey, "currentMaterialKey");
        targetMaterialKey = requireMaterialKey(targetMaterialKey, "targetMaterialKey");
    }

    /** Whether this classification matches the physical action requested by the event. */
    public boolean permits(EnemyTerrainActionKind action) {
        Objects.requireNonNull(action, "action");
        return switch (classification) {
            case BREAKABLE -> withinCombatArea && action == EnemyTerrainActionKind.BREAK;
            case BUILDABLE_GAP -> withinCombatArea
                    && supportAvailable
                    && action == EnemyTerrainActionKind.BUILD;
            case CLEAR, PROTECTED, UNAVAILABLE -> false;
        };
    }

    /** Converts the snapshot into the context consumed by the role-specific planner. */
    public EnemyPathContext toPathContext(int consecutivePathFailures) {
        return switch (classification) {
            case CLEAR -> new EnemyPathContext(
                    true, false, false, false, consecutivePathFailures);
            case PROTECTED -> new EnemyPathContext(
                    false, true, false, false, consecutivePathFailures);
            case BREAKABLE -> new EnemyPathContext(
                    false, false, true, false, consecutivePathFailures);
            case BUILDABLE_GAP -> new EnemyPathContext(
                    false, false, false, true, consecutivePathFailures);
            case UNAVAILABLE -> new EnemyPathContext(
                    false, false, false, false, consecutivePathFailures);
        };
    }

    private static String requireMaterialKey(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
