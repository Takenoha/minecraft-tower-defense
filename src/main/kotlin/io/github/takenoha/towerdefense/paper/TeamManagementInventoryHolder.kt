package io.github.takenoha.towerdefense.paper

import java.util.Objects
import java.util.Optional
import java.util.UUID
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

/** Identifies the team-management inventory and its rendered member slots. */
class TeamManagementInventoryHolder(coreId: UUID) : InventoryHolder {
    private val coreIdValue: UUID = Objects.requireNonNull(coreId, "coreId")
    private var inventory: Inventory? = null
    private var memberSlots: Map<Int, UUID> = emptyMap()

    fun coreId(): UUID = coreIdValue

    fun memberAt(slot: Int): Optional<UUID> = Optional.ofNullable(memberSlots[slot])

    fun attach(inventory: Inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory")
    }

    fun attachMemberSlots(memberSlots: Map<Int, UUID>) {
        this.memberSlots = Objects.requireNonNull(memberSlots, "memberSlots").toMap()
    }

    override fun getInventory(): Inventory =
        inventory ?: throw IllegalStateException("the GUI inventory has not been attached")
}
