package io.github.takenoha.towerdefense.paper

import java.util.Objects
import java.util.UUID
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

/** Carries the target and action for a team-management confirmation screen. */
class TeamManagementConfirmationHolder(
    coreId: UUID,
    targetId: UUID,
    action: Action,
) : InventoryHolder {
    enum class Action {
        REMOVE_MEMBER,
        TRANSFER_OWNER,
        LEAVE_TEAM,
    }

    private val coreIdValue: UUID = Objects.requireNonNull(coreId, "coreId")
    private val targetIdValue: UUID = Objects.requireNonNull(targetId, "targetId")
    private val actionValue: Action = Objects.requireNonNull(action, "action")
    private var inventory: Inventory? = null

    fun coreId(): UUID = coreIdValue

    fun targetId(): UUID = targetIdValue

    fun action(): Action = actionValue

    fun attach(inventory: Inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory")
    }

    override fun getInventory(): Inventory =
        inventory ?: throw IllegalStateException("the GUI inventory has not been attached")
}
