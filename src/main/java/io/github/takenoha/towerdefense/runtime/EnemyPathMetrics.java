package io.github.takenoha.towerdefense.runtime;

import io.github.takenoha.towerdefense.domain.EnemyPathAction;
import java.util.Objects;

/**
 * Main-thread counters for Paper path inspection and role decisions.
 *
 * <p>The counters are deliberately in-memory. They measure the live Paper boundary without
 * adding database writes to the enemy tick, and a terminal log can expose the snapshot for load
 * testing.</p>
 */
public final class EnemyPathMetrics {
    private long inspectionCount;
    private long inspectionFailureCount;
    private long totalInspectionNanos;
    private long maxInspectionNanos;
    private long directPathAcceptedCount;
    private long advanceDecisionCount;
    private long breakObstacleDecisionCount;
    private long buildSupportDecisionCount;
    private long recalculateDecisionCount;
    private long recoverDecisionCount;
    private long bridgeAttemptCount;
    private long bridgePlacementCount;
    private long breakAttemptCount;
    private long breakSuccessCount;

    public void recordInspection(long elapsedNanos) {
        if (elapsedNanos < 0L) {
            throw new IllegalArgumentException("elapsedNanos must not be negative");
        }
        inspectionCount = increment(inspectionCount);
        totalInspectionNanos = add(totalInspectionNanos, elapsedNanos);
        maxInspectionNanos = Math.max(maxInspectionNanos, elapsedNanos);
    }

    public void recordInspectionFailure() {
        inspectionFailureCount = increment(inspectionFailureCount);
    }

    public void recordDecision(boolean directPathAccepted, EnemyPathAction action) {
        Objects.requireNonNull(action, "action");
        if (directPathAccepted) {
            directPathAcceptedCount = increment(directPathAcceptedCount);
        }
        switch (action) {
            case ADVANCE -> advanceDecisionCount = increment(advanceDecisionCount);
            case BREAK_OBSTACLE -> breakObstacleDecisionCount = increment(
                    breakObstacleDecisionCount);
            case BUILD_SUPPORT -> buildSupportDecisionCount = increment(
                    buildSupportDecisionCount);
            case RECALCULATE_PATH -> recalculateDecisionCount = increment(
                    recalculateDecisionCount);
            case RECOVER -> recoverDecisionCount = increment(recoverDecisionCount);
        }
    }

    public void recordBridgeAttempt(boolean placed) {
        bridgeAttemptCount = increment(bridgeAttemptCount);
        if (placed) {
            bridgePlacementCount = increment(bridgePlacementCount);
        }
    }

    public void recordBreakAttempt(boolean broken) {
        breakAttemptCount = increment(breakAttemptCount);
        if (broken) {
            breakSuccessCount = increment(breakSuccessCount);
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
                inspectionCount,
                inspectionFailureCount,
                totalInspectionNanos,
                maxInspectionNanos,
                directPathAcceptedCount,
                advanceDecisionCount,
                breakObstacleDecisionCount,
                buildSupportDecisionCount,
                recalculateDecisionCount,
                recoverDecisionCount,
                bridgeAttemptCount,
                bridgePlacementCount,
                breakAttemptCount,
                breakSuccessCount);
    }

    private static long increment(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private static long add(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    /** Immutable load-test and terminal-log view of the counters. */
    public record Snapshot(
            long inspectionCount,
            long inspectionFailureCount,
            long totalInspectionNanos,
            long maxInspectionNanos,
            long directPathAcceptedCount,
            long advanceDecisionCount,
            long breakObstacleDecisionCount,
            long buildSupportDecisionCount,
            long recalculateDecisionCount,
            long recoverDecisionCount,
            long bridgeAttemptCount,
            long bridgePlacementCount,
            long breakAttemptCount,
            long breakSuccessCount) {
        public Snapshot {
            requireNonNegative(inspectionCount, "inspectionCount");
            requireNonNegative(inspectionFailureCount, "inspectionFailureCount");
            requireNonNegative(totalInspectionNanos, "totalInspectionNanos");
            requireNonNegative(maxInspectionNanos, "maxInspectionNanos");
            requireNonNegative(directPathAcceptedCount, "directPathAcceptedCount");
            requireNonNegative(advanceDecisionCount, "advanceDecisionCount");
            requireNonNegative(breakObstacleDecisionCount, "breakObstacleDecisionCount");
            requireNonNegative(buildSupportDecisionCount, "buildSupportDecisionCount");
            requireNonNegative(recalculateDecisionCount, "recalculateDecisionCount");
            requireNonNegative(recoverDecisionCount, "recoverDecisionCount");
            requireNonNegative(bridgeAttemptCount, "bridgeAttemptCount");
            requireNonNegative(bridgePlacementCount, "bridgePlacementCount");
            requireNonNegative(breakAttemptCount, "breakAttemptCount");
            requireNonNegative(breakSuccessCount, "breakSuccessCount");
            if (maxInspectionNanos > totalInspectionNanos && inspectionCount > 0L) {
                throw new IllegalArgumentException(
                        "maxInspectionNanos must not exceed totalInspectionNanos");
            }
            if (bridgePlacementCount > bridgeAttemptCount) {
                throw new IllegalArgumentException(
                        "bridgePlacementCount must not exceed bridgeAttemptCount");
            }
            if (breakSuccessCount > breakAttemptCount) {
                throw new IllegalArgumentException(
                        "breakSuccessCount must not exceed breakAttemptCount");
            }
        }

        public long averageInspectionNanos() {
            return inspectionCount == 0L ? 0L : totalInspectionNanos / inspectionCount;
        }

        private static void requireNonNegative(long value, String name) {
            if (value < 0L) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
        }
    }
}
