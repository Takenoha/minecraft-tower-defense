package io.github.takenoha.towerdefense.domain

import kotlin.jvm.JvmRecord

/** Immutable, Paper-independent projection of a world's square border. */
@JvmRecord
data class WorldBorderSnapshot(
    val centerX: Double,
    val centerZ: Double,
    val size: Double,
) {
    init {
        requireFinite("centerX", centerX)
        requireFinite("centerZ", centerZ)
        requireFinite("size", size)
        if (size <= 0.0) {
            throw IllegalArgumentException("size must be greater than zero")
        }
    }

    /** Returns whether a horizontal circle fits entirely within this border. */
    fun containsCircle(circleCenterX: Double, circleCenterZ: Double, radius: Double): Boolean {
        requireFinite("circleCenterX", circleCenterX)
        requireFinite("circleCenterZ", circleCenterZ)
        requireFinite("radius", radius)
        if (radius < 0.0) {
            throw IllegalArgumentException("radius must not be negative")
        }

        val halfSize = size / 2.0
        val minX = centerX - halfSize
        val maxX = centerX + halfSize
        val minZ = centerZ - halfSize
        val maxZ = centerZ + halfSize
        return circleCenterX - radius >= minX &&
            circleCenterX + radius <= maxX &&
            circleCenterZ - radius >= minZ &&
            circleCenterZ + radius <= maxZ
    }

    private fun requireFinite(name: String, value: Double) {
        if (!value.isFinite()) {
            throw IllegalArgumentException("$name must be finite")
        }
    }
}
