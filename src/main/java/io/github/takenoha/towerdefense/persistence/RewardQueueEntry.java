package io.github.takenoha.towerdefense.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One idempotently issued, still database-owned reward. */
public record RewardQueueEntry(
        UUID queueId,
        UUID eventId,
        RewardQueueScope scope,
        UUID recipientId,
        String itemId,
        String itemPayload,
        int quantity,
        UUID sourceDropId,
        RewardQueueStatus status,
        UUID issuedOperationId,
        Instant createdAt,
        Instant updatedAt) {
    public RewardQueueEntry {
        Objects.requireNonNull(queueId, "queueId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(sourceDropId, "sourceDropId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(issuedOperationId, "issuedOperationId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        if (itemPayload == null || itemPayload.isBlank()) {
            throw new IllegalArgumentException("itemPayload must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
