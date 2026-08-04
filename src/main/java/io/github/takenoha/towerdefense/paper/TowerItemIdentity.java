package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.domain.TowerType;
import java.util.Objects;
import java.util.UUID;

/** Stable identity carried by one uninstalled tower item. */
public record TowerItemIdentity(UUID towerId, TowerType type, int individualLevel) {
    public TowerItemIdentity {
        Objects.requireNonNull(towerId, "towerId");
        Objects.requireNonNull(type, "type");
        if (individualLevel <= 0) {
            throw new IllegalArgumentException("individualLevel must be positive");
        }
    }
}
