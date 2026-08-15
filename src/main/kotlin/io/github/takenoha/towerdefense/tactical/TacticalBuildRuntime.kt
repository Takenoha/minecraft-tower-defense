package io.github.takenoha.towerdefense.tactical

import java.util.Objects
import java.util.Optional
import java.util.UUID

/** Coordinates idempotent lifecycle calls with the active in-memory effect cache. */
class TacticalBuildRuntime(
    lifecycle: TacticalBuildLifecycle?,
    effects: TacticalEffectCache?,
) : TacticalEffectSnapshotProvider {
    private val lifecycle: TacticalBuildLifecycle =
        Objects.requireNonNull(lifecycle, "lifecycle")!!
    private val effects: TacticalEffectCache =
        Objects.requireNonNull(effects, "effects")!!

    constructor(
        lifecycle: TacticalBuildLifecycle?,
        stateProvider: TacticalBuildStateProvider?,
    ) : this(
        lifecycle,
        TacticalEffectCache(Objects.requireNonNull(stateProvider, "stateProvider")!!),
    )

    companion object {
        @JvmStatic
        fun disabled(): TacticalBuildRuntime = TacticalBuildRuntime(
            object : TacticalBuildLifecycle {
                override fun activateAtPreparation(
                    defenseId: UUID,
                    operationId: UUID,
                ): TacticalUnlockResult = TacticalUnlockResult.unchanged(0)

                override fun advanceAfterWave(
                    defenseId: UUID,
                    completedWaveCount: Int,
                    totalWaveCount: Int,
                    operationId: UUID,
                ): TacticalUnlockResult = TacticalUnlockResult.unchanged(0)

                override fun activateFinalTier(
                    defenseId: UUID,
                    operationId: UUID,
                ): TacticalUnlockResult = TacticalUnlockResult.unchanged(0)

                override fun markTerminal(
                    defenseId: UUID,
                    result: TacticalTerminalResult,
                    operationId: UUID,
                ) {
                    // Deliberately no-op when tactical wiring is not installed yet.
                }
            },
            object : TacticalBuildStateProvider {
                override fun findActiveByDefense(defenseId: UUID): Optional<TacticalBuildSelectionView> =
                    Optional.empty()
            },
        )
    }

    fun activateAtPreparation(
        defenseId: UUID,
        operationId: UUID,
    ): TacticalUnlockResult {
        if (!ensureSelectedBuild(defenseId)) {
            return TacticalUnlockResult.unchanged(0)
        }
        val result = lifecycle.activateAtPreparation(defenseId, operationId)
        effects.rebuild(defenseId)
        return result
    }

    fun advanceAfterWave(
        defenseId: UUID,
        completedWaveCount: Int,
        totalWaveCount: Int,
        operationId: UUID,
    ): TacticalUnlockResult {
        if (!ensureSelectedBuild(defenseId)) {
            return TacticalUnlockResult.unchanged(0)
        }
        val result = lifecycle.advanceAfterWave(
            defenseId,
            completedWaveCount,
            totalWaveCount,
            operationId,
        )
        effects.rebuild(defenseId)
        return result
    }

    fun activateFinalTier(
        defenseId: UUID,
        operationId: UUID,
    ): TacticalUnlockResult {
        if (!ensureSelectedBuild(defenseId)) {
            return TacticalUnlockResult.unchanged(0)
        }
        val result = lifecycle.activateFinalTier(defenseId, operationId)
        effects.rebuild(defenseId)
        return result
    }

    fun rebuild(defenseId: UUID) {
        effects.rebuild(defenseId)
    }

    /** Clears effects after an unsuccessful rebuild or when an active defense is discarded. */
    fun invalidate(defenseId: UUID) {
        effects.invalidate(defenseId)
    }

    fun markTerminal(
        defenseId: UUID,
        result: TacticalTerminalResult,
        operationId: UUID,
    ) {
        if (!ensureSelectedBuild(defenseId)) {
            effects.invalidate(defenseId)
            return
        }
        try {
            lifecycle.markTerminal(defenseId, result, operationId)
        } finally {
            effects.invalidate(defenseId)
        }
    }

    override fun currentForDefense(defenseId: UUID): TacticalEffectSnapshot =
        effects.currentForDefense(defenseId)

    fun effects(): TacticalEffectCache = effects

    private fun ensureSelectedBuild(defenseId: UUID): Boolean {
        if (effects.hasSelectedBuild(defenseId)) {
            return true
        }
        effects.rebuild(defenseId)
        return effects.hasSelectedBuild(defenseId)
    }
}
