package io.github.takenoha.towerdefense.paper

import java.lang.reflect.Method
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.Objects
import java.util.logging.Level
import org.bukkit.World
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin

/**
 * Optional WorldGuard bridge loaded through reflection so WorldGuard remains a soft dependency.
 *
 * Every non-global WorldGuard region whose conservative bounding box intersects the combat circle
 * is treated as protected. This intentionally rejects some polygon corner cases that do not truly
 * overlap, but never allows a potentially protected block area to start an event.
 */
class WorldGuardRegionProtectionAdapter private constructor(
    private val plugin: JavaPlugin,
) : ThirdPartyRegionProtectionAdapter {
    private val regionContainer: Any
    private val adaptWorld: Method
    private val regionManagerForWorld: Method
    private val regions: Method
    private val regionId: Method
    private val minimumPoint: Method
    private val maximumPoint: Method
    private val pointX: Method
    private val pointZ: Method
    private var queryFailureLogged = false

    init {
        Objects.requireNonNull(plugin, "plugin")
        val worldGuardClass = Class.forName("com.sk89q.worldguard.WorldGuard")
        val worldGuard = worldGuardClass.getMethod("getInstance").invoke(null)
        val platform = worldGuardClass.getMethod("getPlatform").invoke(worldGuard)
        regionContainer = platform.javaClass.getMethod("getRegionContainer").invoke(platform)

        val worldEditWorldClass = Class.forName("com.sk89q.worldedit.world.World")
        adaptWorld = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter")
            .getMethod("adapt", World::class.java)
        regionManagerForWorld = regionContainer.javaClass.getMethod("get", worldEditWorldClass)

        val regionManagerClass = Class.forName(
            "com.sk89q.worldguard.protection.managers.RegionManager",
        )
        regions = regionManagerClass.getMethod("getRegions")

        val protectedRegionClass = Class.forName(
            "com.sk89q.worldguard.protection.regions.ProtectedRegion",
        )
        regionId = protectedRegionClass.getMethod("getId")
        minimumPoint = protectedRegionClass.getMethod("getMinimumPoint")
        maximumPoint = protectedRegionClass.getMethod("getMaximumPoint")

        val blockVectorClass = Class.forName("com.sk89q.worldedit.math.BlockVector3")
        pointX = blockVectorClass.getMethod("getX")
        pointZ = blockVectorClass.getMethod("getZ")
    }

    override fun violations(
        world: World?,
        centerX: Double,
        centerZ: Double,
        radius: Double,
    ): List<String> {
        Objects.requireNonNull(world, "world")
        if (!centerX.isFinite() || !centerZ.isFinite() ||
            !radius.isFinite() || radius < 0.0
        ) {
            return Collections.singletonList("protection.third-party: invalid WorldGuard query geometry")
        }

        try {
            val adaptedWorld = adaptWorld.invoke(null, world)
            val regionManager = regionManagerForWorld.invoke(regionContainer, adaptedWorld)
                ?: return emptyList()
            val rawRegions = regions.invoke(regionManager)
            if (rawRegions !is Map<*, *>) {
                return queryFailure("WorldGuard returned an invalid region map", null)
            }

            val intersectingRegionIds = ArrayList<String>()
            for (region in rawRegions.values) {
                if (region == null) {
                    return queryFailure("WorldGuard returned a null region", null)
                }
                val id = regionId.invoke(region) as String?
                if (id == null || id.equals(GLOBAL_REGION_ID, ignoreCase = true)) {
                    continue
                }
                val minimum = minimumPoint.invoke(region)
                val maximum = maximumPoint.invoke(region)
                val minX = (pointX.invoke(minimum) as Number).toDouble()
                val minZ = (pointZ.invoke(minimum) as Number).toDouble()
                val maxX = (pointX.invoke(maximum) as Number).toDouble() + 1.0
                val maxZ = (pointZ.invoke(maximum) as Number).toDouble() + 1.0
                if (circleIntersectsRectangle(centerX, centerZ, radius, minX, minZ, maxX, maxZ)) {
                    intersectingRegionIds.add(id)
                }
            }
            intersectingRegionIds.sortWith(Comparator.naturalOrder())
            val messages = intersectingRegionIds.map { id ->
                "protection.third-party: combat area intersects WorldGuard region ($id)"
            }
            return Collections.unmodifiableList(ArrayList(messages))
        } catch (exception: ReflectiveOperationException) {
            return queryFailure("WorldGuard region query failed", exception)
        } catch (exception: RuntimeException) {
            return queryFailure("WorldGuard region query failed", exception)
        }
    }

    private fun queryFailure(message: String, exception: Exception?): List<String> {
        if (!queryFailureLogged) {
            queryFailureLogged = true
            if (exception == null) {
                plugin.logger.severe("$message; defense starts will fail closed")
            } else {
                plugin.logger.log(
                    Level.SEVERE,
                    "$message; defense starts will fail closed",
                    exception,
                )
            }
        }
        return Collections.singletonList("protection.third-party: WorldGuard region query unavailable")
    }

    companion object {
        private const val WORLDGUARD_PLUGIN = "WorldGuard"
        private const val GLOBAL_REGION_ID = "__global__"

        /**
         * Discovers WorldGuard when it is installed. An installed but disabled or incompatible
         * plugin returns a fail-closed adapter; a server without WorldGuard gets a no-op adapter.
         */
        @JvmStatic
        fun discover(plugin: JavaPlugin): ThirdPartyRegionProtectionAdapter {
            Objects.requireNonNull(plugin, "plugin")
            val worldGuard: Plugin? = plugin.server.pluginManager.getPlugin(WORLDGUARD_PLUGIN)
            if (worldGuard == null) {
                return ThirdPartyRegionProtectionAdapter.none()
            }
            if (!worldGuard.isEnabled) {
                return ThirdPartyRegionProtectionAdapter.unavailable(
                    "protection.third-party: WorldGuard is installed but not enabled",
                )
            }
            return try {
                WorldGuardRegionProtectionAdapter(plugin)
            } catch (exception: ReflectiveOperationException) {
                plugin.logger.log(
                    Level.SEVERE,
                    "WorldGuard was detected but its region API could not be loaded; " +
                        "defense starts will fail closed",
                    exception,
                )
                ThirdPartyRegionProtectionAdapter.unavailable(
                    "protection.third-party: WorldGuard integration is unavailable",
                )
            } catch (exception: RuntimeException) {
                plugin.logger.log(
                    Level.SEVERE,
                    "WorldGuard was detected but its region API could not be loaded; " +
                        "defense starts will fail closed",
                    exception,
                )
                ThirdPartyRegionProtectionAdapter.unavailable(
                    "protection.third-party: WorldGuard integration is unavailable",
                )
            }
        }

        private fun circleIntersectsRectangle(
            centerX: Double,
            centerZ: Double,
            radius: Double,
            minX: Double,
            minZ: Double,
            maxX: Double,
            maxZ: Double,
        ): Boolean {
            val nearestX = maxOf(minX, minOf(centerX, maxX))
            val nearestZ = maxOf(minZ, minOf(centerZ, maxZ))
            val deltaX = centerX - nearestX
            val deltaZ = centerZ - nearestZ
            return Math.hypot(deltaX, deltaZ) <= radius
        }
    }
}
