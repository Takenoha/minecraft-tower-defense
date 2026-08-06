package io.github.takenoha.towerdefense.persistence;

import java.util.Objects;

/** Result of one idempotent event-funds credit or spend operation. */
public record BattleFundsMutationResult(OperationOutcome outcome, BattleFunds funds) {
    public BattleFundsMutationResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(funds, "funds");
    }
}
