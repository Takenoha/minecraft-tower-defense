package io.github.takenoha.towerdefense.persistence;

import java.util.Objects;
import java.util.UUID;

/** Immutable write-ahead description of one event-owned block mutation. */
public record BlockChange(
        UUID eventId,
        UUID changeId,
        UUID worldId,
        int blockX,
        int blockY,
        int blockZ,
        BlockChangeKind kind,
        long generation,
        String beforeBlockData,
        String beforeBlockState,
        String beforeTileNbt,
        String expectedAfterBlockData,
        String expectedAfterBlockState,
        String expectedAfterTileNbt) {
    /** Keeps the pre-Tile-NBT persistence API source-compatible for ordinary blocks. */
    public BlockChange(
            UUID eventId,
            UUID changeId,
            UUID worldId,
            int blockX,
            int blockY,
            int blockZ,
            BlockChangeKind kind,
            long generation,
            String beforeBlockData,
            String beforeBlockState,
            String expectedAfterBlockData,
            String expectedAfterBlockState) {
        this(
                eventId,
                changeId,
                worldId,
                blockX,
                blockY,
                blockZ,
                kind,
                generation,
                beforeBlockData,
                beforeBlockState,
                "",
                expectedAfterBlockData,
                expectedAfterBlockState,
                "");
    }

    public BlockChange {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(changeId, "changeId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(kind, "kind");
        requireText(beforeBlockData, "beforeBlockData");
        requireText(beforeBlockState, "beforeBlockState");
        Objects.requireNonNull(beforeTileNbt, "beforeTileNbt");
        requireText(expectedAfterBlockData, "expectedAfterBlockData");
        requireText(expectedAfterBlockState, "expectedAfterBlockState");
        Objects.requireNonNull(expectedAfterTileNbt, "expectedAfterTileNbt");
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
