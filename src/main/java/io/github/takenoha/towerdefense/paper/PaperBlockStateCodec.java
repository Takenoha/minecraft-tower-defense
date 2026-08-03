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

    /** Captures a block before mutation, including the mutable tile payload when present. */
    public static BlockStateSnapshot captureBefore(Block block) {
        Objects.requireNonNull(block, "block");
        return captureComparable(block);
    }

    /** Captures a block for comparison, including its mutable tile payload. */
    public static BlockStateSnapshot captureComparable(Block block) {
        Objects.requireNonNull(block, "block");
        BlockState state = block.getState();
        return new BlockStateSnapshot(
                block.getBlockData().getAsString(),
                state.getType().getKey().toString(),
                PaperTileNbtCodec.capture(state));
    }

    /** Parses the canonical BlockData string stored in the ledger. */
    public static BlockData parseBlockData(String blockData) {
        Objects.requireNonNull(blockData, "blockData");
        return Bukkit.createBlockData(blockData);
    }

    /** Builds the comparable snapshot for a planned BlockData value. */
    public static BlockStateSnapshot snapshotForBlockData(String blockData) {
        BlockData parsed = parseBlockData(blockData);
        BlockState state = parsed.createBlockState();
        return new BlockStateSnapshot(
                parsed.getAsString(),
                parsed.getMaterial().getKey().toString(),
                PaperTileNbtCodec.capture(state));
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

    /** Applies a durable block snapshot and then updates its tile payload, if any. */
    public static void applySnapshot(Block block, BlockStateSnapshot snapshot) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(snapshot, "snapshot");
        block.setBlockData(parseBlockData(snapshot.blockData()), false);
        if (snapshot.tileNbt().isBlank()) {
            return;
        }
        BlockState state = block.getState();
        PaperTileNbtCodec.apply(state, snapshot.tileNbt());
        if (!state.update(true, false)) {
            throw new IllegalStateException(
                    "Paper rejected the durable tile-state update at " + block.getLocation());
        }
    }
}
