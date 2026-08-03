package io.github.takenoha.towerdefense.runtime;

import io.github.takenoha.towerdefense.domain.EnemyRole;
import java.util.Objects;
import java.util.UUID;

/** Durable logical identity carried by a physical event enemy. */
public record TaggedEnemy(UUID eventId, UUID logicalEnemyId, EnemyRole role) {
    /** Keeps legacy callers safe by treating untyped enemies as normal enemies. */
    public TaggedEnemy(UUID eventId, UUID logicalEnemyId) {
        this(eventId, logicalEnemyId, EnemyRole.NORMAL);
    }

    public TaggedEnemy {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(logicalEnemyId, "logicalEnemyId");
        Objects.requireNonNull(role, "role");
    }
}
