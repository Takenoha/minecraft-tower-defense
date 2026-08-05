package io.github.takenoha.towerdefense.paper;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Identifies the read-only team point wallet screen opened from a core. */
public final class ResourceVaultInventoryHolder implements InventoryHolder {
    private final UUID coreId;
    private Inventory inventory;

    public ResourceVaultInventoryHolder(UUID coreId) {
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
