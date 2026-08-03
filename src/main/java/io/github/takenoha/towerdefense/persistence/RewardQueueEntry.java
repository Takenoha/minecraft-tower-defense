package io.github.takenoha.towerdefense.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
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
        Instant updatedAt,
        Optional<Instant> teamClaimDeadline) {
    /** Keeps direct construction source-compatible with schema v9 callers. */
    public RewardQueueEntry(
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
        this(
                queueId,
                eventId,
                scope,
                recipientId,
                itemId,
                itemPayload,
                quantity,
                sourceDropId,
                status,
                issuedOperationId,
                createdAt,
                updatedAt,
                Optional.empty());
    }

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
        teamClaimDeadline = Objects.requireNonNull(teamClaimDeadline, "teamClaimDeadline");
        if (scope == RewardQueueScope.PLAYER && teamClaimDeadline.isPresent()) {
            throw new IllegalArgumentException("PLAYER queue rows cannot have a team deadline");
        }
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
