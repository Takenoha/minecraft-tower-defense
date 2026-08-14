package io.github.takenoha.towerdefense.paper

import java.util.Objects
import org.bukkit.Material

/** Physical materials used by the durable core identity. */
class CoreMaterialPolicy private constructor() {
    companion object {
        @JvmField
        val CURRENT_ITEM: Material = Material.RESIN_BRICKS

        @JvmField
        val LEGACY_ITEM: Material = Material.NETHER_STAR

        @JvmField
        val CURRENT_BLOCK: Material = Material.DRIED_KELP_BLOCK

        @JvmField
        val LEGACY_BLOCK: Material = Material.BEACON

        @JvmStatic
        fun isCoreItemMaterial(material: Material?): Boolean =
            material == CURRENT_ITEM || material == LEGACY_ITEM

        @JvmStatic
        fun isLegacyItemMaterial(material: Material?): Boolean = material == LEGACY_ITEM

        @JvmStatic
        fun isCoreBlockMaterial(material: Material?): Boolean =
            material == CURRENT_BLOCK || material == LEGACY_BLOCK

        @JvmStatic
        fun isCurrentBlock(material: Material?): Boolean = material == CURRENT_BLOCK

        @JvmStatic
        fun requireCoreItemMaterial(material: Material?) {
            val required = Objects.requireNonNull(material, "material")
            if (!isCoreItemMaterial(required)) {
                throw IllegalArgumentException("unsupported core item material: $required")
            }
        }
    }
}
