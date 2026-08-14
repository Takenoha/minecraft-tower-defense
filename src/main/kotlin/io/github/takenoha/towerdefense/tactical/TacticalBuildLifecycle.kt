package io.github.takenoha.towerdefense.tactical

import java.util.UUID

/** Idempotent lifecycle boundary called by the defense runtime. */
interface TacticalBuildLifecycle {
    fun activateAtPreparation(defenseId: UUID, operationId: UUID): TacticalUnlockResult

    fun advanceAfterWave(
        defenseId: UUID,
        completedWaveCount: Int,
        totalWaveCount: Int,
        operationId: UUID,
    ): TacticalUnlockResult

    fun activateFinalTier(defenseId: UUID, operationId: UUID): TacticalUnlockResult

    fun markTerminal(
        defenseId: UUID,
        result: TacticalTerminalResult,
        operationId: UUID,
    )
}
