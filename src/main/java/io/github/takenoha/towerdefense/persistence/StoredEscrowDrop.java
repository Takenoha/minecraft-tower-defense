package io.github.takenoha.towerdefense.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable escrow row with the amount already claimed by registered participants. */
public record StoredEscrowDrop(
        EscrowDrop drop,
        int claimedQuantity,
        EscrowDropStatus status,
        Instant createdAt,
        Instant updatedAt) {
    public StoredEscrowDrop {
        Objects.requireNonNull(drop, "drop");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (claimedQuantity < 0 || claimedQuantity > drop.quantity()) {
            throw new IllegalArgumentException("claimedQuantity must be within the drop quantity");
        }
    }

    public int remainingQuantity() {
        return drop.quantity() - claimedQuantity;
    }

    public Optional<UUID> displayEntityId() {
        return drop.displayEntityId();
    }
}
