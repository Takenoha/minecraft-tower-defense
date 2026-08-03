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
        String expectedAfterBlockData,
        String expectedAfterBlockState) {
    public BlockChange {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(changeId, "changeId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(kind, "kind");
        requireText(beforeBlockData, "beforeBlockData");
        requireText(beforeBlockState, "beforeBlockState");
        requireText(expectedAfterBlockData, "expectedAfterBlockData");
        requireText(expectedAfterBlockState, "expectedAfterBlockState");
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
