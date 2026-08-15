package io.github.takenoha.towerdefense.paper

import java.util.Objects
import java.util.UUID
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

/** Identifies the team tower-research inventory and its source core. */
class TowerResearchInventoryHolder(coreId: UUID) : InventoryHolder {
    private val coreIdValue: UUID = Objects.requireNonNull(coreId, "coreId")
    private var inventory: Inventory? = null

    fun coreId(): UUID = coreIdValue

    fun attach(inventory: Inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory")
    }

    override fun getInventory(): Inventory =
        inventory ?: throw IllegalStateException("the GUI inventory has not been attached")
}
