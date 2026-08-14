package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.persistence.BlockStateSnapshot
import java.util.Objects
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.TileState
import org.bukkit.block.data.BlockData

/** Serializes the Bukkit block values used by the persistence-only mutation ledger. */
class PaperBlockStateCodec private constructor() {
    companion object {
        /** Captures a block before mutation, including the mutable tile payload when present. */
        @JvmStatic
        fun captureBefore(block: Block): BlockStateSnapshot {
            Objects.requireNonNull(block, "block")
            return captureComparable(block)
        }

        /** Captures a block for comparison, including its mutable tile payload. */
        @JvmStatic
        fun captureComparable(block: Block): BlockStateSnapshot {
            Objects.requireNonNull(block, "block")
            val state = block.state
            return BlockStateSnapshot(
                block.blockData.asString,
                state.type.key.toString(),
                PaperTileNbtCodec.capture(state),
            )
        }

        /** Parses the canonical BlockData string stored in the ledger. */
        @JvmStatic
        fun parseBlockData(blockData: String): BlockData {
            Objects.requireNonNull(blockData, "blockData")
            return Bukkit.createBlockData(blockData)
        }

        /** Builds the comparable snapshot for a planned BlockData value. */
        @JvmStatic
        fun snapshotForBlockData(blockData: String): BlockStateSnapshot {
            val parsed = parseBlockData(blockData)
            val state = parsed.createBlockState()
            return BlockStateSnapshot(
                parsed.asString,
                parsed.material.key.toString(),
                PaperTileNbtCodec.capture(state),
            )
        }

        /** Applies BlockData without allowing vanilla physics to mutate neighboring blocks. */
        @JvmStatic
        fun applyBlockData(block: Block, blockData: String) {
            Objects.requireNonNull(block, "block")
            if (block.state is TileState) {
                throw IllegalStateException(
                    "Event block mutation cannot replace an existing tile entity: " +
                        block.type.key,
                )
            }
            block.setBlockData(parseBlockData(blockData), false)
        }

        /** Applies a durable block snapshot and then updates its tile payload, if any. */
        @JvmStatic
        fun applySnapshot(block: Block, snapshot: BlockStateSnapshot) {
            Objects.requireNonNull(block, "block")
            Objects.requireNonNull(snapshot, "snapshot")
            block.setBlockData(parseBlockData(snapshot.blockData()), false)
            if (snapshot.tileNbt().isBlank()) {
                return
            }
            val state = block.state
            PaperTileNbtCodec.apply(state, snapshot.tileNbt())
            if (!state.update(true, false)) {
                throw IllegalStateException(
                    "Paper rejected the durable tile-state update at " + block.location,
                )
            }
        }
    }
}
