package io.github.takenoha.towerdefense.tactical;

import java.util.UUID;

/** Idempotent lifecycle boundary called by the defense runtime. */
public interface TacticalBuildLifecycle {
    TacticalUnlockResult activateAtPreparation(UUID defenseId, UUID operationId);

    TacticalUnlockResult advanceAfterWave(
            UUID defenseId,
            int completedWaveCount,
            int totalWaveCount,
            UUID operationId);

    TacticalUnlockResult activateFinalTier(UUID defenseId, UUID operationId);

    void markTerminal(UUID defenseId, TacticalTerminalResult result, UUID operationId);
}
