package io.github.takenoha.towerdefense.runtime;

import java.util.Objects;
import java.util.UUID;

/** Durable logical identity carried by a physical event enemy. */
public record TaggedEnemy(UUID eventId, UUID logicalEnemyId) {
    public TaggedEnemy {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(logicalEnemyId, "logicalEnemyId");
    }
}

