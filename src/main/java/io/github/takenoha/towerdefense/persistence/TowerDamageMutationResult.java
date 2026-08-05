package io.github.takenoha.towerdefense.persistence;

import java.util.Objects;
import java.util.UUID;

/** Atomic result of one event-enemy attack against an installed tower. */
public record TowerDamageMutationResult(
        OperationOutcome outcome,
        UUID eventId,
        UUID teamId,
        UUID towerId,
        UUID attackerLogicalEnemyId,
        long damage,
        long remainingHitPoints,
        boolean destroyed) {
    public TowerDamageMutationResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(towerId, "towerId");
        Objects.requireNonNull(attackerLogicalEnemyId, "attackerLogicalEnemyId");
        if (damage <= 0L || remainingHitPoints < 0L
                || (destroyed && remainingHitPoints != 0L)
                || (!destroyed && remainingHitPoints == 0L)) {
            throw new IllegalArgumentException("tower damage result is invalid");
        }
    }
}
