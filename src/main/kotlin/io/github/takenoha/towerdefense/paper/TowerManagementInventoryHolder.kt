package io.github.takenoha.towerdefense.paper

import java.util.Objects
import java.util.UUID
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

/** Identifies the tower-management inventory and its target tower. */
class TowerManagementInventoryHolder(towerId: UUID) : InventoryHolder {
    private val towerIdValue: UUID = Objects.requireNonNull(towerId, "towerId")
    private var inventory: Inventory? = null

    fun towerId(): UUID = towerIdValue

    fun attach(inventory: Inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory")
    }

    override fun getInventory(): Inventory =
        inventory ?: throw IllegalStateException("the GUI inventory has not been attached")
}
