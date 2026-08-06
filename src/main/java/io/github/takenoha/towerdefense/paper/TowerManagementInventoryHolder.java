package io.github.takenoha.towerdefense.paper;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Identifies the tower-management inventory and its target tower. */
public final class TowerManagementInventoryHolder implements InventoryHolder {
    private final UUID towerId;
    private Inventory inventory;

    public TowerManagementInventoryHolder(UUID towerId) {
        this.towerId = Objects.requireNonNull(towerId, "towerId");
    }

    public UUID towerId() {
        return towerId;
    }

    void attach(Inventory inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    @Override
    public Inventory getInventory() {
        if (inventory == null) {
            throw new IllegalStateException("the GUI inventory has not been attached");
        }
        return inventory;
    }
}
