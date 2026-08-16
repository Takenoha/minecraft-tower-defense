package io.github.takenoha.towerdefense.interop;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.DefensePhase;
import io.github.takenoha.towerdefense.runtime.DefenseRuntimeStatus;
import io.github.takenoha.towerdefense.runtime.EnemyPathMetrics;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuntimeStatusKotlinBoundaryAbiTest {
    @Test
    void preservesRecordComponentsAndCompatibilityConstructor() throws Exception {
        assertTrue(DefenseRuntimeStatus.class.isRecord());
        assertTrue(Modifier.isPublic(DefenseRuntimeStatus.class.getModifiers()));
        assertTrue(Modifier.isFinal(DefenseRuntimeStatus.class.getModifiers()));
        assertTrue(List.of(
                "eventId", "teamId", "stageLevel", "phase", "currentWave", "totalWaves",
                "waveMutation", "pendingEnemies", "aliveEnemies", "coreHitPoints", "coreMaximumHitPoints",
                "coreAttackers", "coreAttackCount", "ending", "persistenceFailure", "pathMetrics"
        ).equals(Arrays.stream(DefenseRuntimeStatus.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList()));
        assertNotNull(DefenseRuntimeStatus.class.getConstructor(
                UUID.class, UUID.class, long.class, DefensePhase.class, int.class, int.class,
                io.github.takenoha.towerdefense.domain.WaveMutationSnapshot.class,
                long.class, long.class, long.class, long.class, int.class, long.class,
                boolean.class, String.class, EnemyPathMetrics.Snapshot.class));
        assertNotNull(DefenseRuntimeStatus.class.getConstructor(
                UUID.class, UUID.class, long.class, DefensePhase.class, int.class, int.class,
                long.class, long.class, long.class, long.class, boolean.class, String.class,
                EnemyPathMetrics.Snapshot.class));
    }
}
