package io.github.takenoha.towerdefense.domain;

/**
 * Immutable horizontal combat-area configuration. Vertical position is
 * deliberately absent: the area is a cylinder spanning the world's build height.
 */
public record CombatArea(
        double radius,
        double spawnInner,
        double spawnOuter,
        double minimumCoreDistance,
        double coreGap) {

    public CombatArea {
        requireFinite("radius", radius);
        requireFinite("spawnInner", spawnInner);
        requireFinite("spawnOuter", spawnOuter);
        requireFinite("minimumCoreDistance", minimumCoreDistance);
        requireFinite("coreGap", coreGap);

        if (radius <= 0.0d) {
            throw new IllegalArgumentException("radius must be greater than zero");
        }
        if (spawnInner < 0.0d) {
            throw new IllegalArgumentException("spawnInner must not be negative");
        }
        if (spawnInner >= spawnOuter) {
            throw new IllegalArgumentException("spawnInner must be less than spawnOuter");
        }
        if (spawnOuter > radius) {
            throw new IllegalArgumentException("spawnOuter must not exceed radius");
        }
        if (coreGap < 0.0d) {
            throw new IllegalArgumentException("coreGap must not be negative");
        }

        double requiredCoreDistance = Math.fma(2.0d, radius, coreGap);
        if (!Double.isFinite(requiredCoreDistance)) {
            throw new IllegalArgumentException("radius and coreGap produce an unbounded distance");
        }
        if (minimumCoreDistance < requiredCoreDistance) {
            throw new IllegalArgumentException(
                    "minimumCoreDistance must be at least 2 * radius + coreGap");
        }
    }

    /** Returns the minimum core spacing implied by the radius and configured gap. */
    public double requiredCoreDistance() {
        return Math.fma(2.0d, radius, coreGap);
    }

    /** Returns whether a point lies within the combat cylinder, including its edge. */
    public boolean contains(double centerX, double centerZ, double pointX, double pointZ) {
        return horizontalDistance(centerX, centerZ, pointX, pointZ) <= radius;
    }

    /** Returns whether a point lies in the inclusive enemy spawn band. */
    public boolean isInSpawnBand(
            double centerX, double centerZ, double pointX, double pointZ) {
        double distance = horizontalDistance(centerX, centerZ, pointX, pointZ);
        return distance >= spawnInner && distance <= spawnOuter;
    }

    /** Returns whether two cores satisfy the configured horizontal separation. */
    public boolean coresAreFarEnoughApart(
            double firstX, double firstZ, double secondX, double secondZ) {
        return horizontalDistance(firstX, firstZ, secondX, secondZ) >= minimumCoreDistance;
    }

    /** Computes Euclidean distance in the X/Z plane only. */
    public static double horizontalDistance(
            double firstX, double firstZ, double secondX, double secondZ) {
        requireFinite("firstX", firstX);
        requireFinite("firstZ", firstZ);
        requireFinite("secondX", secondX);
        requireFinite("secondZ", secondZ);
        return Math.hypot(secondX - firstX, secondZ - firstZ);
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
