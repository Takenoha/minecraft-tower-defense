package io.github.takenoha.towerdefense.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable core position and hit points. */
public record CoreRecord(
        UUID id,
        UUID teamId,
        UUID worldId,
        int blockX,
        int blockY,
        int blockZ,
        long currentHitPoints,
        long maximumHitPoints,
        Instant createdAt,
        Instant updatedAt) {
    public CoreRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (maximumHitPoints <= 0) {
            throw new IllegalArgumentException("maximumHitPoints must be positive");
        }
        if (currentHitPoints < 0 || currentHitPoints > maximumHitPoints) {
            throw new IllegalArgumentException(
                    "currentHitPoints must be between zero and maximumHitPoints");
        }
    }
}
