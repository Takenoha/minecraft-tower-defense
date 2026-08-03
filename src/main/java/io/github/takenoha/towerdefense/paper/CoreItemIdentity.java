package io.github.takenoha.towerdefense.paper;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** The durable identity carried by a crafted or itemized core item. */
public record CoreItemIdentity(UUID itemId, Optional<UUID> teamId) {
    public CoreItemIdentity {
        Objects.requireNonNull(itemId, "itemId");
        teamId = Objects.requireNonNull(teamId, "teamId");
    }

    public boolean isBound() {
        return teamId.isPresent();
    }
}
