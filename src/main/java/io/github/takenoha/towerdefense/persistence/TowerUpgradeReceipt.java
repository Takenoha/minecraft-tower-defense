package io.github.takenoha.towerdefense.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One durable material receipt belonging to a legacy tower upgrade. */
public record TowerUpgradeReceipt(
        UUID operationId,
        UUID playerId,
        String material,
        long quantity,
        TowerUpgradeReceiptState state,
        Instant reservedAt,
        Instant resolvedAt) {
    public TowerUpgradeReceipt {
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
