package io.github.takenoha.towerdefense.tactical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.TowerType;
import io.github.takenoha.towerdefense.persistence.OperationOutcome;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TacticalBuildRuntimeTest {
    @Test
    void lifecycleCallsRebuildEffectsAndTerminalInvalidatesCache() {
        UUID defenseId = UUID.randomUUID();
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        TacticalBuildSelectionView selection = new TacticalBuildSelectionView(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "runtime-test",
                1,
                1,
                List.of(new TacticalSkillNodeSnapshot(
                        "node",
                        1,
                        1,
                        "Node",
                        "test",
                        List.of(new TacticalEffectEntry(
                                TacticalEffectType.DAMAGE_MULTIPLIER,
                                Set.of(TowerType.ARROW),
                                1.2d,
                                TacticalTargetCondition.NONE,
                                null,
                                null)))));
        TacticalBuildRuntime runtime = new TacticalBuildRuntime(
                lifecycle,
                ignored -> Optional.of(selection));

        TacticalUnlockResult unlocked = runtime.activateAtPreparation(
                defenseId, UUID.randomUUID());

        assertEquals(OperationOutcome.APPLIED, unlocked.outcome());
        assertEquals(1.2d, runtime.currentForDefense(defenseId).damageMultiplier(
                TowerType.ARROW, TacticalTargetContext.neutral()), 0.000001d);
        assertEquals(1, lifecycle.preparationCalls);

        runtime.markTerminal(
                defenseId,
                TacticalTerminalResult.VICTORY,
                UUID.randomUUID());

        assertEquals(1, lifecycle.terminalCalls);
        assertTrue(runtime.currentForDefense(defenseId)
                == EmptyTacticalEffectSnapshot.INSTANCE);
    }

    @Test
    void unboundDefenseUsesNeutralLifecycleWithoutCallingPersistence() {
        UUID defenseId = UUID.randomUUID();
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        TacticalBuildRuntime runtime = new TacticalBuildRuntime(
                lifecycle,
                ignored -> Optional.empty());

        TacticalUnlockResult unlocked = runtime.activateAtPreparation(
                defenseId, UUID.randomUUID());
        runtime.markTerminal(
                defenseId,
                TacticalTerminalResult.RECOVERY,
                UUID.randomUUID());

        assertEquals(OperationOutcome.ALREADY_APPLIED, unlocked.outcome());
        assertEquals(0, lifecycle.preparationCalls);
        assertEquals(0, lifecycle.terminalCalls);
        assertTrue(runtime.currentForDefense(defenseId)
                == EmptyTacticalEffectSnapshot.INSTANCE);
    }

    private static final class RecordingLifecycle implements TacticalBuildLifecycle {
        private int preparationCalls;
        private int terminalCalls;

        @Override
        public TacticalUnlockResult activateAtPreparation(UUID defenseId, UUID operationId) {
            preparationCalls++;
            return new TacticalUnlockResult(OperationOutcome.APPLIED, 1, List.of("node"));
        }

        @Override
        public TacticalUnlockResult advanceAfterWave(
                UUID defenseId,
                int completedWaveCount,
                int totalWaveCount,
                UUID operationId) {
            return TacticalUnlockResult.unchanged(1);
        }

        @Override
        public TacticalUnlockResult activateFinalTier(UUID defenseId, UUID operationId) {
            return TacticalUnlockResult.unchanged(1);
        }

        @Override
        public void markTerminal(
                UUID defenseId,
                TacticalTerminalResult result,
                UUID operationId) {
            terminalCalls++;
        }
    }
}
