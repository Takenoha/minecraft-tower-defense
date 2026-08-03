package io.github.takenoha.towerdefense.persistence;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable request to create one virtual, non-craftable event drop. */
public record EscrowDrop(
        UUID eventId,
        UUID dropId,
        DropSourceKind sourceKind,
        UUID sourceId,
        String itemId,
        String itemPayload,
        int quantity,
        Optional<UUID> displayEntityId) {
    public EscrowDrop {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(dropId, "dropId");
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(sourceId, "sourceId");
        requireText(itemId, "itemId");
        requireText(itemPayload, "itemPayload");
        displayEntityId = Objects.requireNonNull(displayEntityId, "displayEntityId");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
