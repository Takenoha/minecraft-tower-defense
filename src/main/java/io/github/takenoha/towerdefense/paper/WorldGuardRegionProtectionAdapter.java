package io.github.takenoha.towerdefense.paper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Optional WorldGuard bridge loaded through reflection so WorldGuard remains a soft dependency.
 *
 * <p>Every non-global WorldGuard region whose conservative bounding box intersects the combat
 * circle is treated as protected. This intentionally rejects some polygon corner cases that do
 * not truly overlap, but never allows a potentially protected block area to start an event.</p>
 */
public final class WorldGuardRegionProtectionAdapter
        implements ThirdPartyRegionProtectionAdapter {
    private static final String WORLDGUARD_PLUGIN = "WorldGuard";
    private static final String GLOBAL_REGION_ID = "__global__";

    private final JavaPlugin plugin;
    private final Object regionContainer;
    private final Method adaptWorld;
    private final Method regionManagerForWorld;
    private final Method regions;
    private final Method regionId;
    private final Method minimumPoint;
    private final Method maximumPoint;
    private final Method pointX;
    private final Method pointZ;
    private boolean queryFailureLogged;

    /**
     * Discovers WorldGuard when it is installed. An installed but disabled or incompatible
     * plugin returns a fail-closed adapter; a server without WorldGuard gets a no-op adapter.
     */
    public static ThirdPartyRegionProtectionAdapter discover(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        Plugin worldGuard = plugin.getServer().getPluginManager().getPlugin(WORLDGUARD_PLUGIN);
        if (worldGuard == null) {
            return ThirdPartyRegionProtectionAdapter.none();
        }
        if (!worldGuard.isEnabled()) {
            return ThirdPartyRegionProtectionAdapter.unavailable(
                    "protection.third-party: WorldGuard is installed but not enabled");
        }
        try {
            return new WorldGuardRegionProtectionAdapter(plugin);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "WorldGuard was detected but its region API could not be loaded; "
                            + "defense starts will fail closed",
                    exception);
            return ThirdPartyRegionProtectionAdapter.unavailable(
                    "protection.third-party: WorldGuard integration is unavailable");
        }
    }

    private WorldGuardRegionProtectionAdapter(JavaPlugin plugin)
            throws ReflectiveOperationException {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        Class<?> worldGuardClass = Class.forName("com.sk89q.worldguard.WorldGuard");
        Object worldGuard = worldGuardClass.getMethod("getInstance").invoke(null);
        Object platform = worldGuardClass.getMethod("getPlatform").invoke(worldGuard);
        regionContainer = platform.getClass().getMethod("getRegionContainer").invoke(platform);

        Class<?> worldEditWorldClass = Class.forName("com.sk89q.worldedit.world.World");
        adaptWorld = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter")
                .getMethod("adapt", World.class);
        regionManagerForWorld = regionContainer.getClass().getMethod("get", worldEditWorldClass);

        Class<?> regionManagerClass = Class.forName(
                "com.sk89q.worldguard.protection.managers.RegionManager");
        regions = regionManagerClass.getMethod("getRegions");

        Class<?> protectedRegionClass = Class.forName(
                "com.sk89q.worldguard.protection.regions.ProtectedRegion");
        regionId = protectedRegionClass.getMethod("getId");
        minimumPoint = protectedRegionClass.getMethod("getMinimumPoint");
        maximumPoint = protectedRegionClass.getMethod("getMaximumPoint");

        Class<?> blockVectorClass = Class.forName("com.sk89q.worldedit.math.BlockVector3");
        pointX = blockVectorClass.getMethod("getX");
        pointZ = blockVectorClass.getMethod("getZ");
    }

    @Override
    public List<String> violations(
            World world,
            double centerX,
            double centerZ,
            double radius) {
        Objects.requireNonNull(world, "world");
        if (!Double.isFinite(centerX)
                || !Double.isFinite(centerZ)
                || !Double.isFinite(radius)
                || radius < 0.0d) {
            return List.of("protection.third-party: invalid WorldGuard query geometry");
        }

        try {
            Object adaptedWorld = adaptWorld.invoke(null, world);
            Object regionManager = regionManagerForWorld.invoke(regionContainer, adaptedWorld);
            if (regionManager == null) {
                return List.of();
            }
            Object rawRegions = regions.invoke(regionManager);
            if (!(rawRegions instanceof Map<?, ?> regionMap)) {
                return queryFailure("WorldGuard returned an invalid region map", null);
            }

            List<String> intersectingRegionIds = new ArrayList<>();
            for (Object region : regionMap.values()) {
                if (region == null) {
                    return queryFailure("WorldGuard returned a null region", null);
                }
                String id = (String) regionId.invoke(region);
                if (id == null || id.equalsIgnoreCase(GLOBAL_REGION_ID)) {
                    continue;
                }
                Object minimum = minimumPoint.invoke(region);
                Object maximum = maximumPoint.invoke(region);
                double minX = ((Number) pointX.invoke(minimum)).doubleValue();
                double minZ = ((Number) pointZ.invoke(minimum)).doubleValue();
                double maxX = ((Number) pointX.invoke(maximum)).doubleValue() + 1.0d;
                double maxZ = ((Number) pointZ.invoke(maximum)).doubleValue() + 1.0d;
                if (circleIntersectsRectangle(
                        centerX, centerZ, radius, minX, minZ, maxX, maxZ)) {
                    intersectingRegionIds.add(id);
                }
            }
            intersectingRegionIds.sort(Comparator.naturalOrder());
            return intersectingRegionIds.stream()
                    .map(id -> "protection.third-party: combat area intersects WorldGuard region ("
                            + id + ")")
                    .toList();
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return queryFailure("WorldGuard region query failed", exception);
        }
    }

    private List<String> queryFailure(String message, Exception exception) {
        if (!queryFailureLogged) {
            queryFailureLogged = true;
            if (exception == null) {
                plugin.getLogger().severe(message + "; defense starts will fail closed");
            } else {
                plugin.getLogger().log(
                        Level.SEVERE,
                        message + "; defense starts will fail closed",
                        exception);
            }
        }
        return List.of("protection.third-party: WorldGuard region query unavailable");
    }

    private static boolean circleIntersectsRectangle(
            double centerX,
            double centerZ,
            double radius,
            double minX,
            double minZ,
            double maxX,
            double maxZ) {
        double nearestX = Math.max(minX, Math.min(centerX, maxX));
        double nearestZ = Math.max(minZ, Math.min(centerZ, maxZ));
        double deltaX = centerX - nearestX;
        double deltaZ = centerZ - nearestZ;
        return Math.hypot(deltaX, deltaZ) <= radius;
    }
}
