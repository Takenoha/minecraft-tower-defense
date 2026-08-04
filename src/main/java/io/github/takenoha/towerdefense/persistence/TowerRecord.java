package io.github.takenoha.towerdefense.persistence;

import io.github.takenoha.towerdefense.domain.TowerType;
import io.github.takenoha.towerdefense.domain.TowerTargetPriority;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable identity and physical entity location for one installed tower. */
public record TowerRecord(
        UUID id,
        UUID teamId,
        UUID worldId,
        int blockX,
        int blockY,
        int blockZ,
        TowerType type,
        int individualLevel,
        TowerTargetPriority targetPriority,
        UUID entityId,
        Instant createdAt,
        Instant updatedAt) {
    public TowerRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(targetPriority, "targetPriority");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (individualLevel <= 0) {
            throw new IllegalArgumentException("individualLevel must be positive");
        }
    }

    /** Backward-compatible constructor for records that use the default target priority. */
    public TowerRecord(
            UUID id,
            UUID teamId,
            UUID worldId,
            int blockX,
            int blockY,
            int blockZ,
            TowerType type,
            int individualLevel,
            UUID entityId,
            Instant createdAt,
            Instant updatedAt) {
        this(
                id,
                teamId,
                worldId,
                blockX,
                blockY,
                blockZ,
                type,
                individualLevel,
                TowerTargetPriority.CORE_NEAREST,
                entityId,
                createdAt,
                updatedAt);
    }
}
