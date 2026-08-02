package io.github.takenoha.towerdefense.runtime;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.block.Block;

/** Exact world/block coordinate used to identify a persisted core without chunk access. */
public record CoreBlockKey(UUID worldId, int x, int y, int z) {
    public CoreBlockKey {
        Objects.requireNonNull(worldId, "worldId");
    }

    public static CoreBlockKey from(Block block) {
        Objects.requireNonNull(block, "block");
        return new CoreBlockKey(
                block.getWorld().getUID(),
                block.getX(),
                block.getY(),
                block.getZ());
    }
}

