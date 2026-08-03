package io.github.takenoha.towerdefense.domain;

/** Paper-independent facts supplied to the role-aware navigation planner. */
public record EnemyPathContext(
        boolean directPathAvailable,
        boolean protectedObstacle,
        boolean breakableObstacle,
        boolean buildableGap,
        int consecutivePathFailures) {
    public EnemyPathContext {
        if (consecutivePathFailures < 0) {
            throw new IllegalArgumentException("consecutivePathFailures must not be negative");
        }
    }
}
