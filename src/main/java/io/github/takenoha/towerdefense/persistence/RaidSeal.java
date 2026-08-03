package io.github.takenoha.towerdefense.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** A non-stackable challenge token whose database state is authoritative. */
public record RaidSeal(
        UUID sealId,
        UUID ownerPlayerId,
        long stageLevel,
        RaidSealStatus status,
        Optional<UUID> eventId,
        Optional<UUID> reservationOperationId,
        Optional<UUID> consumptionOperationId,
        Optional<UUID> refundOperationId,
        Instant createdAt,
        Instant updatedAt) {
    public RaidSeal {
        Objects.requireNonNull(sealId, "sealId");
        Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
        Objects.requireNonNull(status, "status");
        eventId = Objects.requireNonNull(eventId, "eventId");
        reservationOperationId = Objects.requireNonNull(
                reservationOperationId, "reservationOperationId");
        consumptionOperationId = Objects.requireNonNull(
                consumptionOperationId, "consumptionOperationId");
        refundOperationId = Objects.requireNonNull(refundOperationId, "refundOperationId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (stageLevel <= 0L) {
            throw new IllegalArgumentException("stageLevel must be positive");
        }
    }
}
