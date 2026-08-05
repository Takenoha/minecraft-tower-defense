package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.persistence.CorePlacement;
import java.util.Objects;
import org.bukkit.block.Block;

/**
 * Checks the physical material at a database-owned core coordinate.
 *
 * <p>The current core block is not a tile entity, so it cannot carry the placement PDC that the
 * former beacon representation used.  The database placement row and {@code CoreRegistry} are
 * the source of truth; callers only invoke these checks at a coordinate from that row.</p>
 */
public final class CoreBlockTagger {
    public CoreBlockTagger(org.bukkit.plugin.Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
    }

    public boolean tag(Block block, CorePlacement placement) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(placement, "placement");
        return CoreMaterialPolicy.isCurrentBlock(block.getType());
    }

    public boolean matches(Block block, CorePlacement placement) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(placement, "placement");
        return CoreMaterialPolicy.isCoreBlockMaterial(block.getType());
    }
}
