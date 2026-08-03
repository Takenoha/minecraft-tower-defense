package io.github.takenoha.towerdefense.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable block ledger row, including its idempotence and recovery markers. */
public record StoredBlockChange(
        BlockChange change,
        BlockChangeStatus status,
        UUID prepareOperationId,
        Optional<UUID> applyOperationId,
        Optional<UUID> rollbackOperationId,
        Instant preparedAt,
        Optional<Instant> appliedAt,
        Optional<Instant> resolvedAt) {
    public StoredBlockChange {
        Objects.requireNonNull(change, "change");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(prepareOperationId, "prepareOperationId");
        applyOperationId = Objects.requireNonNull(applyOperationId, "applyOperationId");
        rollbackOperationId = Objects.requireNonNull(rollbackOperationId, "rollbackOperationId");
        Objects.requireNonNull(preparedAt, "preparedAt");
        appliedAt = Objects.requireNonNull(appliedAt, "appliedAt");
        resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
        if ((status == BlockChangeStatus.PREPARED) != appliedAt.isEmpty()) {
            throw new IllegalArgumentException(
                    "only an applied or resolved block change may have appliedAt");
        }
        if ((status == BlockChangeStatus.ROLLED_BACK || status == BlockChangeStatus.CONFLICT)
                != resolvedAt.isPresent()) {
            throw new IllegalArgumentException(
                    "resolvedAt must match the terminal block ledger status");
        }
    }
}
