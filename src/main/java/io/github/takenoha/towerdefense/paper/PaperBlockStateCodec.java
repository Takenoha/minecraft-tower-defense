package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.persistence.BlockStateSnapshot;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;

/** Serializes the Bukkit block values used by the persistence-only mutation ledger. */
public final class PaperBlockStateCodec {
    private PaperBlockStateCodec() {
    }

    /** Captures a block before mutation and refuses to overwrite an existing tile entity. */
    public static BlockStateSnapshot captureBefore(Block block) {
        Objects.requireNonNull(block, "block");
        BlockState state = block.getState();
        if (state instanceof org.bukkit.block.TileState) {
            throw new IllegalStateException(
                    "Event block mutation cannot overwrite an existing tile entity: "
                            + block.getType().getKey());
        }
        return captureComparable(block);
    }

    /** Captures a block for comparison, including a tile entity's type without changing it. */
    public static BlockStateSnapshot captureComparable(Block block) {
        Objects.requireNonNull(block, "block");
        BlockState state = block.getState();
        return new BlockStateSnapshot(
                block.getBlockData().getAsString(),
                state.getType().getKey().toString());
    }

    /** Parses the canonical BlockData string stored in the ledger. */
    public static BlockData parseBlockData(String blockData) {
        Objects.requireNonNull(blockData, "blockData");
        return Bukkit.createBlockData(blockData);
    }

    /** Builds the comparable snapshot for a planned non-tile BlockData value. */
    public static BlockStateSnapshot snapshotForBlockData(String blockData) {
        BlockData parsed = parseBlockData(blockData);
        return new BlockStateSnapshot(
                parsed.getAsString(),
                parsed.getMaterial().getKey().toString());
    }

    /** Applies BlockData without allowing vanilla physics to mutate neighboring blocks. */
    public static void applyBlockData(Block block, String blockData) {
        Objects.requireNonNull(block, "block");
        if (block.getState() instanceof org.bukkit.block.TileState) {
            throw new IllegalStateException(
                    "Event block mutation cannot replace an existing tile entity: "
                            + block.getType().getKey());
        }
        block.setBlockData(parseBlockData(blockData), false);
    }
}
