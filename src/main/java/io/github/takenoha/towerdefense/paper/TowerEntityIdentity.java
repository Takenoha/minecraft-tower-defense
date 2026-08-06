package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.domain.TowerType;
import java.util.Objects;
import java.util.UUID;

/** PDC identity carried by the persistent Paper entity representing a tower. */
public record TowerEntityIdentity(UUID towerId, UUID teamId, TowerType type, int individualLevel) {
    public TowerEntityIdentity {
        Objects.requireNonNull(towerId, "towerId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(type, "type");
        if (individualLevel <= 0) {
            throw new IllegalArgumentException("individualLevel must be positive");
        }
    }
}
