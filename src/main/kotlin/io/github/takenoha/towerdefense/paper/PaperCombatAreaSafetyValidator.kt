package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.config.ProtectionSettings
import io.github.takenoha.towerdefense.domain.CombatArea
import io.github.takenoha.towerdefense.domain.CombatAreaSafetyValidator
import io.github.takenoha.towerdefense.domain.ThirdPartyRegionProbe
import io.github.takenoha.towerdefense.domain.WorldBorderSnapshot
import java.util.ArrayList
import java.util.Objects
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.WorldBorder

/** Adapts Paper's loaded-world border to the Paper-independent safety validator. */
class PaperCombatAreaSafetyValidator private constructor() {
    companion object {
        /** Returns start/placement violations for a combat circle centered in the loaded world. */
        @JvmStatic
        fun violations(
            world: World?,
            centerX: Double,
            centerZ: Double,
            combatArea: CombatArea,
            protection: ProtectionSettings,
        ): List<String> = violations(
            world,
            centerX,
            centerZ,
            combatArea,
            protection,
            ThirdPartyRegionProtectionAdapter.none(),
        )

        /** Returns start/placement violations including an optional region-plugin query. */
        @JvmStatic
        fun violations(
            world: World?,
            centerX: Double,
            centerZ: Double,
            combatArea: CombatArea,
            protection: ProtectionSettings,
            regionProtection: ThirdPartyRegionProtectionAdapter,
        ): List<String> {
            if (world == null) {
                return listOf("world: is not loaded")
            }
            Objects.requireNonNull(combatArea, "combatArea")
            Objects.requireNonNull(protection, "protection")
            Objects.requireNonNull(regionProtection, "regionProtection")
            val border: WorldBorder = Objects.requireNonNull(world.worldBorder, "world border")
            val borderCenter: Location = Objects.requireNonNull(
                border.center,
                "world border center",
            )
            val snapshot = WorldBorderSnapshot(
                borderCenter.x,
                borderCenter.z,
                border.size,
            )
            val violations = ArrayList(
                CombatAreaSafetyValidator.violations(
                    world.name,
                    centerX,
                    centerZ,
                    combatArea,
                    protection,
                    snapshot,
                    ThirdPartyRegionProbe { _, ignoredCenterX, ignoredCenterZ, radius ->
                        regionProtection.violations(
                            world,
                            ignoredCenterX,
                            ignoredCenterZ,
                            radius,
                        )
                    },
                ),
            )
            return java.util.List.copyOf(violations)
        }
    }
}
