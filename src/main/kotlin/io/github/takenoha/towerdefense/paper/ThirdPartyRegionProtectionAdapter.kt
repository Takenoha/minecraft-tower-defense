package io.github.takenoha.towerdefense.paper

import java.util.Objects
import org.bukkit.World

/** Main-thread adapter for an optional third-party region protection plugin. */
fun interface ThirdPartyRegionProtectionAdapter {
    /** Returns violations when the combat circle overlaps protected third-party regions. */
    fun violations(
        world: World?,
        centerX: Double,
        centerZ: Double,
        radius: Double,
    ): List<String>

    companion object {
        /** Returns an adapter for servers without a third-party region plugin. */
        @JvmStatic
        fun none(): ThirdPartyRegionProtectionAdapter =
            ThirdPartyRegionProtectionAdapter { _, _, _, _ -> emptyList() }

        /** Returns a fail-closed adapter for an installed but unavailable integration. */
        @JvmStatic
        fun unavailable(reason: String): ThirdPartyRegionProtectionAdapter {
            val message = Objects.requireNonNull(reason, "reason")
            return ThirdPartyRegionProtectionAdapter { _, _, _, _ -> listOf(message) }
        }
    }
}
