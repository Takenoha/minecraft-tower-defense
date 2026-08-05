package io.github.takenoha.towerdefense.persistence;

import java.util.Objects;
import java.util.Optional;

/** Result of an idempotent escrow claim attempt. */
public record EscrowClaimResult(
        OperationOutcome outcome,
        int claimedQuantity,
        Optional<ResourcePickupFeedback> pickupFeedback) {
    public EscrowClaimResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(pickupFeedback, "pickupFeedback");
        if (claimedQuantity < 0) {
            throw new IllegalArgumentException("claimedQuantity must not be negative");
        }
    }

    public EscrowClaimResult(OperationOutcome outcome, int claimedQuantity) {
        this(outcome, claimedQuantity, Optional.empty());
    }
}
