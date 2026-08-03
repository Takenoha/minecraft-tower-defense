package io.github.takenoha.towerdefense.paper;

import java.util.Objects;
import java.util.UUID;

/** PDC identity attached to a physical display of a database-owned escrow drop. */
public record TaggedEscrowDrop(UUID eventId, UUID dropId) {
    public TaggedEscrowDrop {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(dropId, "dropId");
    }
}
