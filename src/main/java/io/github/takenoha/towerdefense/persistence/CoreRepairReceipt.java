package io.github.takenoha.towerdefense.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Database receipt for the vanilla material stack reserved by a core repair. */
public record CoreRepairReceipt(
        UUID operationId,
        UUID playerId,
        String material,
        long quantity,
        CoreRepairReceiptState state,
        Instant reservedAt,
        Instant resolvedAt) {
    public CoreRepairReceipt {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(reservedAt, "reservedAt");
        if (quantity <= 0L) {
            throw new IllegalArgumentException("receipt quantity must be positive");
        }
    }
}
