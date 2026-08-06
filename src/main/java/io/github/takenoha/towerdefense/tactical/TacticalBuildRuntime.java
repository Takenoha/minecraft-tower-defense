package io.github.takenoha.towerdefense.tactical;

import java.util.Optional;
import java.util.UUID;

/** Coordinates idempotent lifecycle calls with the active in-memory effect cache. */
public final class TacticalBuildRuntime implements TacticalEffectSnapshotProvider {
    private final TacticalBuildLifecycle lifecycle;
    private final TacticalEffectCache effects;

    public TacticalBuildRuntime(
            TacticalBuildLifecycle lifecycle,
            TacticalBuildStateProvider stateProvider) {
        this(
                lifecycle,
                new TacticalEffectCache(stateProvider));
    }

    public TacticalBuildRuntime(
            TacticalBuildLifecycle lifecycle,
            TacticalEffectCache effects) {
        this.lifecycle = java.util.Objects.requireNonNull(lifecycle, "lifecycle");
        this.effects = java.util.Objects.requireNonNull(effects, "effects");
    }

    public static TacticalBuildRuntime disabled() {
        return new TacticalBuildRuntime(
                new TacticalBuildLifecycle() {
                    @Override
                    public TacticalUnlockResult activateAtPreparation(
                            UUID defenseId, UUID operationId) {
                        return TacticalUnlockResult.unchanged(0);
                    }

                    @Override
                    public TacticalUnlockResult advanceAfterWave(
                            UUID defenseId,
                            int completedWaveCount,
                            int totalWaveCount,
                            UUID operationId) {
                        return TacticalUnlockResult.unchanged(0);
                    }

                    @Override
                    public TacticalUnlockResult activateFinalTier(UUID defenseId, UUID operationId) {
                        return TacticalUnlockResult.unchanged(0);
                    }

                    @Override
                    public void markTerminal(
                            UUID defenseId,
                            TacticalTerminalResult result,
                            UUID operationId) {
                        // Deliberately no-op when tactical wiring is not installed yet.
                    }
                },
                defenseId -> Optional.empty());
    }

    public TacticalUnlockResult activateAtPreparation(UUID defenseId, UUID operationId) {
        TacticalUnlockResult result = lifecycle.activateAtPreparation(defenseId, operationId);
        effects.rebuild(defenseId);
        return result;
    }

    public TacticalUnlockResult advanceAfterWave(
            UUID defenseId,
            int completedWaveCount,
            int totalWaveCount,
            UUID operationId) {
        TacticalUnlockResult result = lifecycle.advanceAfterWave(
                defenseId,
                completedWaveCount,
                totalWaveCount,
                operationId);
        effects.rebuild(defenseId);
        return result;
    }

    public TacticalUnlockResult activateFinalTier(UUID defenseId, UUID operationId) {
        TacticalUnlockResult result = lifecycle.activateFinalTier(defenseId, operationId);
        effects.rebuild(defenseId);
        return result;
    }

    public void rebuild(UUID defenseId) {
        effects.rebuild(defenseId);
    }

    /** Clears effects after an unsuccessful rebuild or when an active defense is discarded. */
    public void invalidate(UUID defenseId) {
        effects.invalidate(defenseId);
    }

    public void markTerminal(
            UUID defenseId,
            TacticalTerminalResult result,
            UUID operationId) {
        try {
            lifecycle.markTerminal(defenseId, result, operationId);
        } finally {
            effects.invalidate(defenseId);
        }
    }

    public TacticalEffectSnapshot currentForDefense(UUID defenseId) {
        return effects.currentForDefense(defenseId);
    }

    public TacticalEffectCache effects() {
        return effects;
    }
}
