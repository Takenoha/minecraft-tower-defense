package io.github.takenoha.towerdefense.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One participant's durable claim against an escrowed drop. */
public record EscrowClaim(
        UUID eventId,
        UUID dropId,
        UUID recipientId,
        int quantity,
        UUID operationId,
        Instant claimedAt) {
    public EscrowClaim {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(dropId, "dropId");
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(claimedAt, "claimedAt");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
