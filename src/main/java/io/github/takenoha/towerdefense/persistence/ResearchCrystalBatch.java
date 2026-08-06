package io.github.takenoha.towerdefense.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Team-bound, idempotently issued research-crystal quantity. */
public record ResearchCrystalBatch(
        UUID batchId,
        UUID eventId,
        UUID teamId,
        long stageLevel,
        int issuedQuantity,
        int redeemedQuantity,
        ResearchCrystalBatchStatus status,
        Instant createdAt,
        Instant updatedAt) {
    public ResearchCrystalBatch {
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (stageLevel <= 0L) {
            throw new IllegalArgumentException("stageLevel must be positive");
        }
        if (issuedQuantity <= 0) {
            throw new IllegalArgumentException("issuedQuantity must be positive");
        }
        if (redeemedQuantity < 0 || redeemedQuantity > issuedQuantity) {
            throw new IllegalArgumentException(
                    "redeemedQuantity must be between zero and issuedQuantity");
        }
    }

    public int remainingQuantity() {
        return issuedQuantity - redeemedQuantity;
    }
}
