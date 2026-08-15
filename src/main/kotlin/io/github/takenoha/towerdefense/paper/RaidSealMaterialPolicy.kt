package io.github.takenoha.towerdefense.paper

import java.util.Objects

/** Material compatibility policy for the current and legacy raid-seal items. */
class RaidSealMaterialPolicy private constructor() {
    companion object {
        const val CURRENT_MATERIAL: String = "ECHO_SHARD"
        const val LEGACY_MATERIAL: String = "ENDER_EYE"

        @JvmStatic
        fun supports(materialName: String): Boolean {
            Objects.requireNonNull(materialName, "materialName")
            return CURRENT_MATERIAL == materialName || LEGACY_MATERIAL == materialName
        }

        @JvmStatic
        fun isLegacy(materialName: String): Boolean {
            Objects.requireNonNull(materialName, "materialName")
            return LEGACY_MATERIAL == materialName
        }
    }
}
