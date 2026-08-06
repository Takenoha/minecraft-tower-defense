package io.github.takenoha.towerdefense.tactical;

import io.github.takenoha.towerdefense.persistence.OperationOutcome;
import java.util.List;
import java.util.Objects;

/** Idempotent result returned after an automatic tier-activation request. */
public record TacticalUnlockResult(
        OperationOutcome outcome,
        int highestUnlockedTier,
        List<String> newlyUnlockedNodeIds) {
    public TacticalUnlockResult {
        outcome = Objects.requireNonNull(outcome, "outcome");
        if (highestUnlockedTier < 0 || highestUnlockedTier > 6) {
            throw new IllegalArgumentException("highestUnlockedTier must be between 0 and 6");
        }
        Objects.requireNonNull(newlyUnlockedNodeIds, "newlyUnlockedNodeIds");
        newlyUnlockedNodeIds = List.copyOf(newlyUnlockedNodeIds);
    }

    public static TacticalUnlockResult unchanged(int highestUnlockedTier) {
        return new TacticalUnlockResult(
                OperationOutcome.ALREADY_APPLIED,
                highestUnlockedTier,
                List.of());
    }
}
