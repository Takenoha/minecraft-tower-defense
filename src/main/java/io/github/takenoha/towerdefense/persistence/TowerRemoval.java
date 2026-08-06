package io.github.takenoha.towerdefense.persistence;

import io.github.takenoha.towerdefense.domain.TowerType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Prepared physical-stop-window intent for removing one installed tower. */
public record TowerRemoval(
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
        UUID entityId,
        TowerRemovalState state,
        Instant preparedAt,
        Instant appliedAt,
        Instant rolledBackAt) {
    public TowerRemoval {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(towerId, "towerId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(preparedAt, "preparedAt");
        if (individualLevel <= 0) {
            throw new IllegalArgumentException("individualLevel must be positive");
        }
        if (state == TowerRemovalState.PREPARED
                && (appliedAt != null || rolledBackAt != null)) {
            throw new IllegalArgumentException("a prepared removal has no terminal timestamp");
        }
        if (state == TowerRemovalState.APPLIED
                && (appliedAt == null || rolledBackAt != null)) {
            throw new IllegalArgumentException("an applied removal requires appliedAt only");
        }
        if (state == TowerRemovalState.ROLLED_BACK
                && (appliedAt != null || rolledBackAt == null)) {
            throw new IllegalArgumentException(
                    "a rolled-back removal requires rolledBackAt only");
        }
    }

    public static TowerRemoval prepared(
            UUID operationId,
            TowerRecord tower,
            UUID actorId,
            Instant preparedAt) {
        Objects.requireNonNull(tower, "tower");
        return new TowerRemoval(
                operationId,
                tower.id(),
                actorId,
                tower.teamId(),
                tower.worldId(),
                tower.blockX(),
                tower.blockY(),
                tower.blockZ(),
                tower.type(),
                tower.individualLevel(),
                tower.entityId(),
                TowerRemovalState.PREPARED,
                preparedAt,
                null,
                null);
    }
}
