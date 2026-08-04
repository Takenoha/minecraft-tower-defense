package io.github.takenoha.towerdefense.paper;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Carries the target and action for a team-management confirmation screen. */
public final class TeamManagementConfirmationHolder implements InventoryHolder {
    public enum Action {
        REMOVE_MEMBER,
        TRANSFER_OWNER,
        LEAVE_TEAM
    }

    private final UUID coreId;
    private final UUID targetId;
    private final Action action;
    private Inventory inventory;

    public TeamManagementConfirmationHolder(UUID coreId, UUID targetId, Action action) {
        this.coreId = Objects.requireNonNull(coreId, "coreId");
        this.targetId = Objects.requireNonNull(targetId, "targetId");
        this.action = Objects.requireNonNull(action, "action");
    }

    public UUID coreId() {
        return coreId;
    }

    public UUID targetId() {
        return targetId;
    }

    public Action action() {
        return action;
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
