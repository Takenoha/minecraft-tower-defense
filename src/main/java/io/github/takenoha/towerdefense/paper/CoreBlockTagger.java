package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.persistence.CorePlacement;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/** Stores the durable placement identity on the physical beacon block. */
public final class CoreBlockTagger {
    private final NamespacedKey operationIdKey;
    private final NamespacedKey coreIdKey;
    private final NamespacedKey itemIdKey;
    private final NamespacedKey teamIdKey;

    public CoreBlockTagger(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        operationIdKey = new NamespacedKey(plugin, "core_placement_operation_id");
        coreIdKey = new NamespacedKey(plugin, "core_id");
        itemIdKey = new NamespacedKey(plugin, "core_item_id");
        teamIdKey = new NamespacedKey(plugin, "core_team_id");
    }

    public boolean tag(Block block, CorePlacement placement) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(placement, "placement");
        BlockState state = block.getState();
        if (!(state instanceof TileState tile)) {
            return false;
        }
        PersistentDataContainer data = tile.getPersistentDataContainer();
        data.set(operationIdKey, PersistentDataType.STRING, placement.operationId().toString());
        data.set(coreIdKey, PersistentDataType.STRING, placement.coreId().toString());
        data.set(itemIdKey, PersistentDataType.STRING, placement.itemId().toString());
        data.set(teamIdKey, PersistentDataType.STRING, placement.teamId().toString());
        return tile.update(true, false);
    }

    public boolean matches(Block block, CorePlacement placement) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(placement, "placement");
        BlockState state = block.getState();
        if (!(state instanceof TileState tile)) {
            return false;
        }
        PersistentDataContainer data = tile.getPersistentDataContainer();
        return placement.operationId().toString().equals(
                        data.get(operationIdKey, PersistentDataType.STRING))
                && placement.coreId().toString().equals(
                        data.get(coreIdKey, PersistentDataType.STRING))
                && placement.itemId().toString().equals(
                        data.get(itemIdKey, PersistentDataType.STRING))
                && placement.teamId().toString().equals(
                        data.get(teamIdKey, PersistentDataType.STRING));
    }

    public Optional<UUID> operationId(Block block) {
        Objects.requireNonNull(block, "block");
        BlockState state = block.getState();
        if (!(state instanceof TileState tile)) {
            return Optional.empty();
        }
        String value = tile.getPersistentDataContainer().get(
                operationIdKey, PersistentDataType.STRING);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException invalidId) {
            return Optional.empty();
        }
    }
}
