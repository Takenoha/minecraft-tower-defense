package io.github.takenoha.towerdefense.paper;

import java.util.Objects;
import org.bukkit.Material;

/**
 * The physical materials used by the durable core identity.
 *
 * <p>Core identity is still PDC-backed for inventory items, but the placed block is identified
 * by the database-backed core coordinate.  This is necessary because the current placed block,
 * dried kelp, is not a tile entity and cannot carry a persistent data container.</p>
 */
public final class CoreMaterialPolicy {
    public static final Material CURRENT_ITEM = Material.RESIN_BRICKS;
    public static final Material LEGACY_ITEM = Material.NETHER_STAR;
    public static final Material CURRENT_BLOCK = Material.DRIED_KELP_BLOCK;
    public static final Material LEGACY_BLOCK = Material.BEACON;

    private CoreMaterialPolicy() {
    }

    public static boolean isCoreItemMaterial(Material material) {
        return material == CURRENT_ITEM || material == LEGACY_ITEM;
    }

    public static boolean isLegacyItemMaterial(Material material) {
        return material == LEGACY_ITEM;
    }

    public static boolean isCoreBlockMaterial(Material material) {
        return material == CURRENT_BLOCK || material == LEGACY_BLOCK;
    }

    public static boolean isCurrentBlock(Material material) {
        return material == CURRENT_BLOCK;
    }

    public static void requireCoreItemMaterial(Material material) {
        if (!isCoreItemMaterial(Objects.requireNonNull(material, "material"))) {
            throw new IllegalArgumentException("unsupported core item material: " + material);
        }
    }
}
