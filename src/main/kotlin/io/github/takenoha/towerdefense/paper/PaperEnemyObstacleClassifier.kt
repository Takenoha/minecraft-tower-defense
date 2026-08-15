package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.domain.EnemyObstacleFacts
import io.github.takenoha.towerdefense.runtime.CoreRegistry
import io.github.takenoha.towerdefense.runtime.EnemyAccessPolicy
import io.github.takenoha.towerdefense.runtime.EnemyObstacleClassifier
import io.github.takenoha.towerdefense.runtime.TerrainMutationPolicy
import java.util.Objects
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.block.TileState
import org.bukkit.block.data.BlockData
import org.bukkit.inventory.InventoryHolder

/** Captures the live Paper block facts needed by the role-aware obstacle classifier. */
class PaperEnemyObstacleClassifier private constructor() {
    companion object {
        /**
         * Reads the candidate and its support on the Paper main thread without mutating either.
         * Unknown, protected, or out-of-area state is returned as a non-actionable classification.
         */
        @JvmStatic
        fun classify(
            candidate: Block,
            target: BlockData,
            cores: CoreRegistry,
            accessPolicy: EnemyAccessPolicy,
        ): EnemyObstacleFacts {
            requireMainThread()
            Objects.requireNonNull(candidate, "candidate")
            Objects.requireNonNull(target, "target")
            Objects.requireNonNull(cores, "cores")
            Objects.requireNonNull(accessPolicy, "accessPolicy")

            val currentState: BlockState = candidate.state
            val targetState: BlockState = target.createBlockState()
            val support: Block = candidate.getRelative(BlockFace.DOWN)
            val supportState: BlockState = support.state
            val withinCombatArea = accessPolicy.isCombatAreaProtected(candidate.location)
            val supportAvailable = withinCombatArea &&
                support.isSolid &&
                accessPolicy.isCombatAreaProtected(support.location) &&
                !isProtectedSupport(support, supportState, cores)
            return EnemyObstacleClassifier.classify(
                candidate.type.key.toString(),
                candidate.isReplaceable,
                currentState is InventoryHolder,
                cores.isCore(candidate),
                currentState is TileState,
                target.material.key.toString(),
                targetState is TileState,
                supportAvailable,
                withinCombatArea,
            )
        }

        private fun isProtectedSupport(
            support: Block,
            supportState: BlockState,
            cores: CoreRegistry,
        ): Boolean =
            cores.isCore(support) ||
                supportState is InventoryHolder ||
                supportState is TileState ||
                TerrainMutationPolicy.isRequiredMaterial(support.type.key.toString())

        private fun requireMainThread() {
            if (!Bukkit.isPrimaryThread()) {
                throw IllegalStateException(
                    "Enemy obstacle classification must run on the main thread",
                )
            }
        }
    }
}
