package io.github.takenoha.towerdefense.persistence;

import io.github.takenoha.towerdefense.domain.TowerType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Prepared physical-stop-window intent for one tower placement. */
public record TowerPlacement(
        UUID operationId,
        UUID towerId,
        UUID actorId,
        UUID teamId,
        UUID worldId,
        int blockX,
        int blockY,
        int blockZ,
        TowerType type,
        int individualLevel,
        TowerPlacementState state,
        Instant preparedAt,
        Instant appliedAt,
        Instant rolledBackAt) {
    public TowerPlacement {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(towerId, "towerId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(preparedAt, "preparedAt");
        if (individualLevel <= 0) {
            throw new IllegalArgumentException("individualLevel must be positive");
        }
        if (state == TowerPlacementState.PREPARED
                && (appliedAt != null || rolledBackAt != null)) {
            throw new IllegalArgumentException("a prepared placement has no terminal timestamp");
        }
        if (state == TowerPlacementState.APPLIED
                && (appliedAt == null || rolledBackAt != null)) {
            throw new IllegalArgumentException("an applied placement requires appliedAt only");
        }
        if (state == TowerPlacementState.ROLLED_BACK
                && (appliedAt != null || rolledBackAt == null)) {
            throw new IllegalArgumentException("a rolled-back placement requires rolledBackAt only");
        }
    }

    public static TowerPlacement prepared(
            UUID operationId,
            UUID towerId,
            UUID actorId,
            UUID teamId,
            UUID worldId,
            int blockX,
            int blockY,
            int blockZ,
            TowerType type,
            Instant preparedAt) {
        return prepared(
                operationId,
                towerId,
                actorId,
                teamId,
                worldId,
                blockX,
                blockY,
                blockZ,
                type,
                1,
                preparedAt);
    }

    public static TowerPlacement prepared(
            UUID operationId,
            UUID towerId,
            UUID actorId,
            UUID teamId,
            UUID worldId,
            int blockX,
            int blockY,
            int blockZ,
            TowerType type,
            int individualLevel,
            Instant preparedAt) {
        return new TowerPlacement(
                operationId,
                towerId,
                actorId,
                teamId,
                worldId,
                blockX,
                blockY,
                blockZ,
                type,
                individualLevel,
                TowerPlacementState.PREPARED,
                preparedAt,
                null,
                null);
    }
}
