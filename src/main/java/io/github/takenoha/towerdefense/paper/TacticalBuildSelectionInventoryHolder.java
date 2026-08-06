package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.tactical.TacticalCandidateSet;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Identifies the pre-defense tactical selection GUI and its non-consumptive start context. */
public final class TacticalBuildSelectionInventoryHolder implements InventoryHolder {
    private final UUID tacticalSessionId;
    private final UUID coreId;
    private final long stage;
    private final UUID sealId;
    private final UUID ownerId;
    private final TacticalCandidateSet candidates;
    private Inventory inventory;
    private String selectedBuildId;
    private boolean confirming;

    public TacticalBuildSelectionInventoryHolder(
            UUID tacticalSessionId,
            UUID coreId,
            long stage,
            UUID sealId,
            UUID ownerId,
            TacticalCandidateSet candidates) {
        this.tacticalSessionId = Objects.requireNonNull(tacticalSessionId, "tacticalSessionId");
        this.coreId = Objects.requireNonNull(coreId, "coreId");
        this.sealId = Objects.requireNonNull(sealId, "sealId");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        if (stage <= 0 || stage != candidates.stage()) {
            throw new IllegalArgumentException("selection stage does not match candidates");
        }
        this.stage = stage;
    }

    public UUID tacticalSessionId() {
        return tacticalSessionId;
    }

    public UUID coreId() {
        return coreId;
    }

    public long stage() {
        return stage;
    }

    public UUID sealId() {
        return sealId;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public TacticalCandidateSet candidates() {
        return candidates;
    }

    public Optional<String> selectedBuildId() {
        return Optional.ofNullable(selectedBuildId);
    }

    public void select(String buildId) {
        candidates.requireBuild(buildId);
        selectedBuildId = buildId;
    }

    public void markConfirming() {
        confirming = true;
    }

    public boolean confirming() {
        return confirming;
    }

    void attach(Inventory inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    @Override
    public Inventory getInventory() {
        if (inventory == null) {
            throw new IllegalStateException("the tactical selection inventory has not been attached");
        }
        return inventory;
    }
}
