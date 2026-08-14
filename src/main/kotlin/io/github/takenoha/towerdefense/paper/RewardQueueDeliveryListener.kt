package io.github.takenoha.towerdefense.paper

import java.util.Objects
import org.bukkit.entity.Item
import org.bukkit.entity.ItemFrame
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockDispenseEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityPortalEvent
import org.bukkit.event.entity.EntityTeleportEvent
import org.bukkit.event.entity.ItemDespawnEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack

/** Hooks durable reward retries to Paper's player lifecycle and protects uncommitted receipts. */
class RewardQueueDeliveryListener(deliveries: RewardQueueDeliveryManager) : Listener {
    private val deliveriesValue = Objects.requireNonNull(deliveries, "deliveries")
    private val tagger = deliveriesValue.tagger()

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        deliveriesValue.onPlayerJoin(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        deliveriesValue.onPlayerQuit(event.player)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        if (tagger.isTagged(event.currentItem) || tagger.isTagged(event.cursor)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (tagger.isTagged(event.oldCursor) || event.newItems.values.any { tagger.isTagged(it) }) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryMove(event: InventoryMoveItemEvent) {
        if (tagger.isTagged(event.item)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryPickup(event: InventoryPickupItemEvent) {
        if (tagger.isTagged(event.item.itemStack)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        if (tagger.isTagged(event.item.itemStack)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (tagger.isTagged(event.itemDrop.itemStack)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCraft(event: CraftItemEvent) {
        val matrix: Array<ItemStack?>? = event.inventory.matrix
        if (matrix != null && matrix.any { tagger.isTagged(it) }) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (tagger.isTagged(event.itemInHand)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDispense(event: BlockDispenseEvent) {
        if (tagger.isTagged(event.item)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (tagger.isTagged(event.item)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) {
        if (tagger.isTagged(event.item)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (event.rightClicked !is ItemFrame) {
            return
        }
        if (tagger.isTagged(event.player.inventory.getItem(event.hand))) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) {
        event.drops.removeIf { itemStack -> tagger.isTagged(itemStack) }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMerge(event: ItemMergeEvent) {
        if (tagger.isTagged(event.entity.itemStack) || tagger.isTagged(event.target.itemStack)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDespawn(event: ItemDespawnEvent) {
        if (tagger.isTagged(event.entity.itemStack)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val item = event.entity as? Item ?: return
        if (tagger.isTagged(item.itemStack)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPortal(event: EntityPortalEvent) {
        val item = event.entity as? Item ?: return
        if (tagger.isTagged(item.itemStack)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTeleport(event: EntityTeleportEvent) {
        val item = event.entity as? Item ?: return
        if (tagger.isTagged(item.itemStack)) {
            event.isCancelled = true
        }
    }
}
