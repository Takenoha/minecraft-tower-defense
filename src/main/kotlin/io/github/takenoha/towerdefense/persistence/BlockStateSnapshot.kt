package io.github.takenoha.towerdefense.persistence

import java.util.Objects
import kotlin.jvm.JvmRecord

/** The serialised block data, block-state data, and tile payload observed in the world. */
@JvmRecord
data class BlockStateSnapshot(
    val blockData: String,
    val blockState: String,
    val tileNbt: String,
) {
    /** Keeps callers which only need ordinary BlockData snapshots source-compatible. */
    constructor(blockData: String, blockState: String) : this(blockData, blockState, "")

    init {
        if (blockData.isBlank()) {
            throw IllegalArgumentException("blockData must not be blank")
        }
        if (blockState.isBlank()) {
            throw IllegalArgumentException("blockState must not be blank")
        }
        Objects.requireNonNull(tileNbt, "tileNbt")
        Objects.requireNonNull(blockData, "blockData")
        Objects.requireNonNull(blockState, "blockState")
    }
}
