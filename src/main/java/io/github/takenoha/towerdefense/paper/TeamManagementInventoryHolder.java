package io.github.takenoha.towerdefense.paper;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Identifies the team-management inventory and its rendered member slots. */
public final class TeamManagementInventoryHolder implements InventoryHolder {
    private final UUID coreId;
    private Inventory inventory;
    private Map<Integer, UUID> memberSlots = Map.of();

    public TeamManagementInventoryHolder(UUID coreId) {
        this.coreId = Objects.requireNonNull(coreId, "coreId");
    }

    public UUID coreId() {
        return coreId;
    }

    public Optional<UUID> memberAt(int slot) {
        return Optional.ofNullable(memberSlots.get(slot));
    }

    void attach(Inventory inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    void attachMemberSlots(Map<Integer, UUID> memberSlots) {
        this.memberSlots = Map.copyOf(Objects.requireNonNull(memberSlots, "memberSlots"));
    }

    @Override
    public Inventory getInventory() {
        if (inventory == null) {
            throw new IllegalStateException("the GUI inventory has not been attached");
        }
        return inventory;
    }
}
