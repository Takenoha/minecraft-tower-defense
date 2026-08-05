package io.github.takenoha.towerdefense.persistence;

import java.util.Objects;

/** Atomic result of a battle-boost purchase and its battle-funds spend. */
public record BattleBoostMutationResult(
        OperationOutcome outcome,
        BattleBoost boost,
        BattleFunds funds) {
    public BattleBoostMutationResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(boost, "boost");
        Objects.requireNonNull(funds, "funds");
    }
}
