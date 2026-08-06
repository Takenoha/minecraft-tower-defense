package io.github.takenoha.towerdefense.persistence;

import java.util.Objects;

/** Atomic result of a battle-funds tower repair. */
public record TowerRepairMutationResult(
        OperationOutcome outcome,
        TowerDurability durability,
        BattleFunds funds) {
    public TowerRepairMutationResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(durability, "durability");
        Objects.requireNonNull(funds, "funds");
    }
}
