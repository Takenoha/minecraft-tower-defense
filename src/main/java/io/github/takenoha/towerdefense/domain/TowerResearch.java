package io.github.takenoha.towerdefense.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable research cap for one tower type owned by a team. */
public record TowerResearch(
        UUID teamId,
        TowerType towerType,
        int researchLevel,
        Instant updatedAt) {
    public TowerResearch {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(towerType, "towerType");
        if (researchLevel <= 0) {
            throw new IllegalArgumentException("researchLevel must be positive");
        }
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static TowerResearch initial(UUID teamId, TowerType towerType, Instant createdAt) {
        return new TowerResearch(teamId, towerType, 1, createdAt);
    }
}
