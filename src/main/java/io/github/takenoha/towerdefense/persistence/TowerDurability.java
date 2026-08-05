package io.github.takenoha.towerdefense.persistence;

import java.util.Objects;
import java.util.UUID;

/** Durable current/max HP snapshot returned by a tower repair mutation. */
public record TowerDurability(UUID towerId, UUID teamId, long currentHitPoints, long maximumHitPoints) {
    public TowerDurability {
        Objects.requireNonNull(towerId, "towerId");
        Objects.requireNonNull(teamId, "teamId");
        if (maximumHitPoints <= 0L
                || currentHitPoints < 0L
                || currentHitPoints > maximumHitPoints) {
            throw new IllegalArgumentException("tower durability is invalid");
        }
    }
}
