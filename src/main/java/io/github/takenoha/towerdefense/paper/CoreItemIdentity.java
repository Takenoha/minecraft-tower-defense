package io.github.takenoha.towerdefense.paper;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** The durable identity carried by a crafted or itemized core item. */
public record CoreItemIdentity(UUID itemId, Optional<UUID> teamId, Optional<UUID> coreId) {
    public CoreItemIdentity(UUID itemId, Optional<UUID> teamId) {
        this(itemId, teamId, Optional.empty());
    }

    public CoreItemIdentity {
        Objects.requireNonNull(itemId, "itemId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        coreId = Objects.requireNonNull(coreId, "coreId");
    }

    public boolean isBound() {
        return teamId.isPresent();
    }
}
