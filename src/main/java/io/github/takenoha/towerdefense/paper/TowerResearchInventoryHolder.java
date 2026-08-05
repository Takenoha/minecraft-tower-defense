package io.github.takenoha.towerdefense.paper;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Identifies the team tower-research inventory and its source core. */
public final class TowerResearchInventoryHolder implements InventoryHolder {
    private final UUID coreId;
    private Inventory inventory;

    public TowerResearchInventoryHolder(UUID coreId) {
        this.coreId = Objects.requireNonNull(coreId, "coreId");
    }

    public UUID coreId() {
        return coreId;
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
