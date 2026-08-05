package io.github.takenoha.towerdefense.persistence;

import java.util.Objects;
import java.util.Optional;

/** Result of applying one idempotent tower upgrade operation. */
public record TowerUpgradeResult(
        OperationOutcome outcome,
        Optional<TowerRecord> tower) {
    public TowerUpgradeResult {
        Objects.requireNonNull(outcome, "outcome");
        tower = Objects.requireNonNull(tower, "tower");
    }
}
