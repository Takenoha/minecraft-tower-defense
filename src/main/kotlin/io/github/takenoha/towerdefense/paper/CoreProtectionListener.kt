package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.runtime.CoreRegistry
import java.util.Objects
import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent

/** Prevents non-plugin world mechanics from moving or destroying a registered core. */
class CoreProtectionListener(cores: CoreRegistry) : Listener {
    private val coresValue = Objects.requireNonNull(cores, "cores")

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (coresValue.isCore(event.block)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (movesCore(event.blocks, event.direction)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (movesCore(event.blocks, event.direction)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityExplosion(event: EntityExplodeEvent) {
        event.blockList().removeIf { block -> coresValue.isCore(block) }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockExplosion(event: BlockExplodeEvent) {
        event.blockList().removeIf { block -> coresValue.isCore(block) }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onLiquid(event: BlockFromToEvent) {
        if (coresValue.isCore(event.toBlock)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityBlockChange(event: EntityChangeBlockEvent) {
        if (coresValue.isCore(event.block)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBurn(event: BlockBurnEvent) {
        if (coresValue.isCore(event.block)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onFade(event: BlockFadeEvent) {
        if (coresValue.isCore(event.block)) {
            event.isCancelled = true
        }
    }

    private fun movesCore(blocks: Iterable<Block>, direction: org.bukkit.block.BlockFace): Boolean {
        for (block in blocks) {
            if (coresValue.isCore(block) || coresValue.isCore(block.getRelative(direction))) {
                return true
            }
        }
        return false
    }
}
