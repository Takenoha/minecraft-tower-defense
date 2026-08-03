package io.github.takenoha.towerdefense.persistence;

import java.util.Objects;

/** Result of an idempotent escrow claim attempt. */
public record EscrowClaimResult(OperationOutcome outcome, int claimedQuantity) {
    public EscrowClaimResult {
        Objects.requireNonNull(outcome, "outcome");
        if (claimedQuantity < 0) {
            throw new IllegalArgumentException("claimedQuantity must not be negative");
        }
    }
}
