package io.github.takenoha.towerdefense.domain;

/** Immutable, Paper-independent projection of a world's square border. */
public record WorldBorderSnapshot(double centerX, double centerZ, double size) {

    public WorldBorderSnapshot {
        requireFinite("centerX", centerX);
        requireFinite("centerZ", centerZ);
        requireFinite("size", size);
        if (size <= 0.0d) {
            throw new IllegalArgumentException("size must be greater than zero");
        }
    }

    /** Returns whether a horizontal circle fits entirely within this border. */
    public boolean containsCircle(double circleCenterX, double circleCenterZ, double radius) {
        requireFinite("circleCenterX", circleCenterX);
        requireFinite("circleCenterZ", circleCenterZ);
        requireFinite("radius", radius);
        if (radius < 0.0d) {
            throw new IllegalArgumentException("radius must not be negative");
        }

        double halfSize = size / 2.0d;
        double minX = centerX - halfSize;
        double maxX = centerX + halfSize;
        double minZ = centerZ - halfSize;
        double maxZ = centerZ + halfSize;
        return circleCenterX - radius >= minX
                && circleCenterX + radius <= maxX
                && circleCenterZ - radius >= minZ
                && circleCenterZ + radius <= maxZ;
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
