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
        long currentHitPoints,
        long maximumHitPoints,
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
        if (individualLevel <= 0 || maximumHitPoints <= 0L
                || currentHitPoints < 0L || currentHitPoints > maximumHitPoints) {
            throw new IllegalArgumentException("tower level or hit points are invalid");
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
                100L,
                100L,
                entityId,
                createdAt,
                updatedAt);
    }

    public TowerRecord withCurrentHitPoints(long hitPoints, Instant updatedAt) {
        return new TowerRecord(
                id,
                teamId,
                worldId,
                blockX,
                blockY,
                blockZ,
                type,
                individualLevel,
                targetPriority,
                hitPoints,
                maximumHitPoints,
                entityId,
                createdAt,
                updatedAt);
    }
}
