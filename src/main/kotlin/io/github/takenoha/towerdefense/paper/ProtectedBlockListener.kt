package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.runtime.CoreRegistry
import io.github.takenoha.towerdefense.runtime.EnemyAccessPolicy
import io.github.takenoha.towerdefense.runtime.TerrainMutationPolicy
import java.util.Objects
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.block.TileState
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockGrowEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent

/** Keeps protected targets physically synchronized while a combat area is active. */
class ProtectedBlockListener(
    cores: CoreRegistry,
    accessPolicy: EnemyAccessPolicy,
) : Listener {
    private val coresValue = Objects.requireNonNull(cores, "cores")
    private val accessPolicyValue = Objects.requireNonNull(accessPolicy, "accessPolicy")

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (isProtected(event.block)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (isProtected(event.blockPlaced)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (movesProtected(event.block, event.blocks, event.direction)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (movesProtected(event.block, event.blocks, event.direction)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityExplosion(event: EntityExplodeEvent) {
        event.blockList().removeIf { block -> isProtected(block) }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockExplosion(event: BlockExplodeEvent) {
        event.blockList().removeIf { block -> isProtected(block) }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onLiquid(event: BlockFromToEvent) {
        if (isProtected(event.block) || isProtected(event.toBlock)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onGrow(event: BlockGrowEvent) {
        if (isProtected(event.block) || isProtectedTarget(event.block, event.newState)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onFade(event: BlockFadeEvent) {
        if (isProtected(event.block) || isProtectedTarget(event.block, event.newState)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBurn(event: BlockBurnEvent) {
        if (isProtected(event.block)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onIgnite(event: BlockIgniteEvent) {
        if (isProtected(event.block)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPhysics(event: BlockPhysicsEvent) {
        if (isProtected(event.block) ||
            isProtectedTarget(event.block, event.changedBlockData.createBlockState())
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (isProtected(event.block, event.to.key.toString(), false)) {
            event.isCancelled = true
        }
    }

    private fun isProtected(block: Block): Boolean {
        Objects.requireNonNull(block, "block")
        return isProtected(
            block,
            block.type.key.toString(),
            block.state is TileState,
        )
    }

    private fun isProtectedTarget(block: Block, target: BlockState): Boolean {
        Objects.requireNonNull(target, "target")
        return isProtected(
            block,
            target.type.key.toString(),
            target is TileState,
        )
    }

    private fun isProtected(block: Block, materialKey: String, tileState: Boolean): Boolean {
        if (coresValue.isCore(block)) {
            return true
        }
        return isProtectedAt(block.location, materialKey, tileState)
    }

    private fun isProtectedAt(location: Location, materialKey: String, tileState: Boolean): Boolean {
        return accessPolicyValue.isCombatAreaProtected(location) &&
            (tileState || TerrainMutationPolicy.isRequiredMaterial(materialKey))
    }

    private fun movesProtected(piston: Block, blocks: List<Block>, direction: BlockFace): Boolean {
        if (isProtected(piston)) {
            return true
        }
        for (block in blocks) {
            val destination = block.getRelative(direction)
            if (isProtected(block) ||
                isProtected(destination) ||
                isProtectedAt(
                    destination.location,
                    block.type.key.toString(),
                    block.state is TileState,
                )
            ) {
                return true
            }
        }
        return false
    }
}
