package io.github.takenoha.towerdefense.domain;

import java.util.Objects;

/** Chooses a bounded role-specific response without touching Paper or world state. */
public final class EnemyRolePlanner {
    public static final int NORMAL_BREAK_FAILURE_THRESHOLD = 3;
    public static final int RECOVERY_FAILURE_THRESHOLD = 3;

    private EnemyRolePlanner() {
    }

    public static EnemyPathAction decide(EnemyRole role, EnemyPathContext context) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(context, "context");
        if (context.directPathAvailable()) {
            return EnemyPathAction.ADVANCE;
        }
        if (context.protectedObstacle()) {
            return context.consecutivePathFailures() >= RECOVERY_FAILURE_THRESHOLD
                    ? EnemyPathAction.RECOVER
                    : EnemyPathAction.RECALCULATE_PATH;
        }
        if (role == EnemyRole.DESTROYER && context.breakableObstacle()) {
            return EnemyPathAction.BREAK_OBSTACLE;
        }
        if (role == EnemyRole.BUILDER && context.buildableGap()) {
            return EnemyPathAction.BUILD_SUPPORT;
        }
        if (role == EnemyRole.NORMAL
                && context.breakableObstacle()
                && context.consecutivePathFailures() >= NORMAL_BREAK_FAILURE_THRESHOLD) {
            return EnemyPathAction.BREAK_OBSTACLE;
        }
        return context.consecutivePathFailures() >= RECOVERY_FAILURE_THRESHOLD
                ? EnemyPathAction.RECOVER
                : EnemyPathAction.RECALCULATE_PATH;
    }
}
