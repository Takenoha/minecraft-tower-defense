package io.github.takenoha.towerdefense.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable intent and physical-before snapshot for one public core placement. */
public record CorePlacement(
        UUID operationId,
        UUID itemId,
        UUID coreId,
        UUID actorId,
        UUID teamId,
        UUID worldId,
        int blockX,
        int blockY,
        int blockZ,
        long maximumHitPoints,
        double minimumCoreDistance,
        boolean rebuildingDestroyedCore,
        boolean relocatingExistingCore,
        String previousBlockData,
        CorePlacementState state,
        Instant preparedAt,
        Instant appliedAt,
        Instant rolledBackAt) {
    public CorePlacement {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(coreId, "coreId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(worldId, "worldId");
        if (maximumHitPoints <= 0L) {
            throw new IllegalArgumentException("maximumHitPoints must be positive");
        }
        if (rebuildingDestroyedCore && relocatingExistingCore) {
            throw new IllegalArgumentException(
                    "a placement cannot rebuild and relocate at the same time");
        }
        if (!Double.isFinite(minimumCoreDistance) || minimumCoreDistance <= 0.0d) {
            throw new IllegalArgumentException("minimumCoreDistance must be finite and positive");
        }
        if (previousBlockData == null || previousBlockData.isBlank()) {
            throw new IllegalArgumentException("previousBlockData must not be blank");
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(preparedAt, "preparedAt");
        if (state == CorePlacementState.PREPARED
                && (appliedAt != null || rolledBackAt != null)) {
            throw new IllegalArgumentException("prepared placement cannot have a terminal timestamp");
        }
        if (state == CorePlacementState.APPLIED
                && (appliedAt == null || rolledBackAt != null)) {
            throw new IllegalArgumentException("applied placement requires only appliedAt");
        }
        if (state == CorePlacementState.ROLLED_BACK
                && (rolledBackAt == null || appliedAt != null)) {
            throw new IllegalArgumentException("rolled-back placement requires only rolledBackAt");
        }
    }

    /** Compatibility constructor for placements created before the relocation flag existed. */
    public CorePlacement(
            UUID operationId,
            UUID itemId,
            UUID coreId,
            UUID actorId,
            UUID teamId,
            UUID worldId,
            int blockX,
            int blockY,
            int blockZ,
            long maximumHitPoints,
            double minimumCoreDistance,
            boolean rebuildingDestroyedCore,
            String previousBlockData,
            CorePlacementState state,
            Instant preparedAt,
            Instant appliedAt,
            Instant rolledBackAt) {
        this(
                operationId,
                itemId,
                coreId,
                actorId,
                teamId,
                worldId,
                blockX,
                blockY,
                blockZ,
                maximumHitPoints,
                minimumCoreDistance,
                rebuildingDestroyedCore,
                false,
                previousBlockData,
                state,
                preparedAt,
                appliedAt,
                rolledBackAt);
    }

    public static CorePlacement prepared(
            UUID operationId,
            UUID itemId,
            UUID coreId,
            UUID actorId,
            UUID teamId,
            UUID worldId,
            int blockX,
            int blockY,
            int blockZ,
            long maximumHitPoints,
            double minimumCoreDistance,
            boolean rebuildingDestroyedCore,
            String previousBlockData,
            Instant preparedAt) {
        return prepared(
                operationId,
                itemId,
                coreId,
                actorId,
                teamId,
                worldId,
                blockX,
                blockY,
                blockZ,
                maximumHitPoints,
                minimumCoreDistance,
                rebuildingDestroyedCore,
                false,
                previousBlockData,
                preparedAt);
    }

    public static CorePlacement prepared(
            UUID operationId,
            UUID itemId,
            UUID coreId,
            UUID actorId,
            UUID teamId,
            UUID worldId,
            int blockX,
            int blockY,
            int blockZ,
            long maximumHitPoints,
            double minimumCoreDistance,
            boolean rebuildingDestroyedCore,
            boolean relocatingExistingCore,
            String previousBlockData,
            Instant preparedAt) {
        return new CorePlacement(
                operationId,
                itemId,
                coreId,
                actorId,
                teamId,
                worldId,
                blockX,
                blockY,
                blockZ,
                maximumHitPoints,
                minimumCoreDistance,
                rebuildingDestroyedCore,
                relocatingExistingCore,
                previousBlockData,
                CorePlacementState.PREPARED,
                preparedAt,
                null,
                null);
    }

    public static CorePlacement preparedRelocation(
            UUID operationId,
            UUID itemId,
            UUID coreId,
            UUID actorId,
            UUID teamId,
            UUID worldId,
            int blockX,
            int blockY,
            int blockZ,
            long maximumHitPoints,
            double minimumCoreDistance,
            String previousBlockData,
            Instant preparedAt) {
        return prepared(
                operationId,
                itemId,
                coreId,
                actorId,
                teamId,
                worldId,
                blockX,
                blockY,
                blockZ,
                maximumHitPoints,
                minimumCoreDistance,
                false,
                true,
                previousBlockData,
                preparedAt);
    }
}
