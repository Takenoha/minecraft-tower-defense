package io.github.takenoha.towerdefense.persistence;

import java.util.Objects;
import java.util.UUID;

/** Values used by the main-thread pickup feedback after a committed claim. */
public record ResourcePickupFeedback(
        UUID eventId,
        UUID playerId,
        ResourceType resourceType,
        int claimedQuantity,
        long eventPlayerTotal,
        long teamBalance) {
    public ResourcePickupFeedback {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(resourceType, "resourceType");
        if (claimedQuantity <= 0 || eventPlayerTotal < 0 || teamBalance < 0) {
            throw new IllegalArgumentException("pickup feedback quantities are invalid");
        }
    }
}
