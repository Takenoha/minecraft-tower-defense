package io.github.takenoha.towerdefense.persistence;

import java.util.Objects;

/** The serialised block data, block-state data, and tile payload observed in the world. */
public record BlockStateSnapshot(String blockData, String blockState, String tileNbt) {
    /** Keeps callers which only need ordinary BlockData snapshots source-compatible. */
    public BlockStateSnapshot(String blockData, String blockState) {
        this(blockData, blockState, "");
    }

    public BlockStateSnapshot {
        if (blockData == null || blockData.isBlank()) {
            throw new IllegalArgumentException("blockData must not be blank");
        }
        if (blockState == null || blockState.isBlank()) {
            throw new IllegalArgumentException("blockState must not be blank");
        }
        if (tileNbt == null) {
            throw new NullPointerException("tileNbt");
        }
        Objects.requireNonNull(blockData, "blockData");
        Objects.requireNonNull(blockState, "blockState");
    }
}
