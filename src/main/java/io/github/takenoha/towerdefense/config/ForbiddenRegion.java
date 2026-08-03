package io.github.takenoha.towerdefense.config;

import java.util.Locale;

/**
 * A horizontal, inclusive region in which a defense combat area may not be placed.
 * The region intentionally has no Bukkit dependency so it can be validated before a
 * Paper event is started.
 */
public record ForbiddenRegion(
        String worldName,
        double minX,
        double minZ,
        double maxX,
        double maxZ) {

    /** Returns whether the point is inside this region, including its boundary. */
    public boolean contains(String candidateWorld, double x, double z) {
        return sameWorld(candidateWorld)
                && x >= minX
                && x <= maxX
                && z >= minZ
                && z <= maxZ;
    }

    /**
     * Returns whether this region intersects a horizontal combat circle.
     * The closest point on the rectangle is used, so edge and corner contact are rejected.
     */
    public boolean intersectsCircle(
            String candidateWorld,
            double centerX,
            double centerZ,
            double radius) {
        if (!sameWorld(candidateWorld)) {
            return false;
        }
        double closestX = Math.max(minX, Math.min(centerX, maxX));
        double closestZ = Math.max(minZ, Math.min(centerZ, maxZ));
        double distanceX = centerX - closestX;
        double distanceZ = centerZ - closestZ;
        return Math.fma(distanceX, distanceX, distanceZ * distanceZ)
                <= radius * radius;
    }

    private boolean sameWorld(String candidateWorld) {
        return candidateWorld != null
                && worldName != null
                && worldName.toLowerCase(Locale.ROOT)
                        .equals(candidateWorld.toLowerCase(Locale.ROOT));
    }
}
