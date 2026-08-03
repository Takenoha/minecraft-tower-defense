package io.github.takenoha.towerdefense.persistence;

import java.util.Objects;

/** The serialised block data and block-state data observed in the world. */
public record BlockStateSnapshot(String blockData, String blockState) {
    public BlockStateSnapshot {
        if (blockData == null || blockData.isBlank()) {
            throw new IllegalArgumentException("blockData must not be blank");
        }
        if (blockState == null || blockState.isBlank()) {
            throw new IllegalArgumentException("blockState must not be blank");
        }
        Objects.requireNonNull(blockData, "blockData");
        Objects.requireNonNull(blockState, "blockState");
    }
}
