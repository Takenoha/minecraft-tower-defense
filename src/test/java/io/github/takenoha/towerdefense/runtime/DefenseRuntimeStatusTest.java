package io.github.takenoha.towerdefense.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.takenoha.towerdefense.domain.DefensePhase;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class DefenseRuntimeStatusTest {
    @Test
    void exposesAnImmutablePathMetricsSnapshotForAdminObservation() {
        EnemyPathMetrics.Snapshot metrics = new EnemyPathMetrics().snapshot();

        DefenseRuntimeStatus status = new DefenseRuntimeStatus(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1L,
                DefensePhase.WAVE_ACTIVE,
                1,
                5,
                2L,
                3L,
                100L,
                100L,
                2,
                7L,
                false,
                null,
                metrics);

        assertSame(metrics, status.pathMetrics());
        assertEquals(2, status.coreAttackers());
        assertEquals(7L, status.coreAttackCount());
    }

    @Test
    void rejectsMissingPathMetrics() {
        assertThrows(
                NullPointerException.class,
                () -> new DefenseRuntimeStatus(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1L,
                        DefensePhase.COUNTDOWN,
                        0,
                        5,
                        0L,
                        0L,
                        100L,
                        100L,
                        false,
                        null,
                        null));
    }
}
