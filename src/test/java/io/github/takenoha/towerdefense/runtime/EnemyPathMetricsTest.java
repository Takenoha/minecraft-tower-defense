package io.github.takenoha.towerdefense.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.takenoha.towerdefense.domain.EnemyPathAction;
import org.junit.jupiter.api.Test;

final class EnemyPathMetricsTest {
    @Test
    void recordsInspectionLatencyDecisionsAndTerrainOutcomesWithoutPersistenceWrites() {
        EnemyPathMetrics metrics = new EnemyPathMetrics();

        metrics.recordInspection(10L);
        metrics.recordInspection(30L);
        metrics.recordDecision(true, EnemyPathAction.ADVANCE);
        metrics.recordDecision(false, EnemyPathAction.BUILD_SUPPORT);
        metrics.recordDecision(false, EnemyPathAction.RECALCULATE_PATH);
        metrics.recordBridgeAttempt(false);
        metrics.recordBridgeAttempt(true);
        metrics.recordBreakAttempt(false);
        metrics.recordBreakAttempt(true);

        EnemyPathMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(2L, snapshot.inspectionCount());
        assertEquals(0L, snapshot.inspectionFailureCount());
        assertEquals(40L, snapshot.totalInspectionNanos());
        assertEquals(30L, snapshot.maxInspectionNanos());
        assertEquals(20L, snapshot.averageInspectionNanos());
        assertEquals(1L, snapshot.directPathAcceptedCount());
        assertEquals(1L, snapshot.advanceDecisionCount());
        assertEquals(1L, snapshot.buildSupportDecisionCount());
        assertEquals(1L, snapshot.recalculateDecisionCount());
        assertEquals(2L, snapshot.bridgeAttemptCount());
        assertEquals(1L, snapshot.bridgePlacementCount());
        assertEquals(2L, snapshot.breakAttemptCount());
        assertEquals(1L, snapshot.breakSuccessCount());
    }

    @Test
    void rejectsInvalidLatencyAndInconsistentSnapshots() {
        EnemyPathMetrics metrics = new EnemyPathMetrics();

        assertThrows(
                IllegalArgumentException.class,
                () -> metrics.recordInspection(-1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EnemyPathMetrics.Snapshot(
                        1L,
                        0L,
                        1L,
                        2L,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EnemyPathMetrics.Snapshot(
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        1L,
                        0L,
                        0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EnemyPathMetrics.Snapshot(
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        1L,
                        2L));
    }
}
