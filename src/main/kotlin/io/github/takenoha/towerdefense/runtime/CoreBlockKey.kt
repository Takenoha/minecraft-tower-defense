package io.github.takenoha.towerdefense.runtime

import java.util.Objects
import java.util.UUID
import kotlin.jvm.JvmRecord
import org.bukkit.block.Block

/** Exact world/block coordinate used to identify a persisted core without chunk access. */
@JvmRecord
data class CoreBlockKey(
    val worldId: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
) {
    init {
        Objects.requireNonNull(worldId, "worldId")
    }

    companion object {
        @JvmStatic
        fun from(block: Block): CoreBlockKey {
            Objects.requireNonNull(block, "block")
            return CoreBlockKey(block.world.uid, block.x, block.y, block.z)
        }
    }
}
