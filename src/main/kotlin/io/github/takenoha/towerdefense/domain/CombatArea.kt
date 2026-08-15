package io.github.takenoha.towerdefense.domain

import kotlin.jvm.JvmRecord

/** Immutable horizontal combat-area configuration. Vertical position is deliberately absent. */
@JvmRecord
data class CombatArea(
    val radius: Double,
    val spawnInner: Double,
    val spawnOuter: Double,
    val minimumCoreDistance: Double,
    val coreGap: Double,
) {
    init {
        requireFinite("radius", radius)
        requireFinite("spawnInner", spawnInner)
        requireFinite("spawnOuter", spawnOuter)
        requireFinite("minimumCoreDistance", minimumCoreDistance)
        requireFinite("coreGap", coreGap)

        if (radius <= 0.0) {
            throw IllegalArgumentException("radius must be greater than zero")
        }
        if (spawnInner < 0.0) {
            throw IllegalArgumentException("spawnInner must not be negative")
        }
        if (spawnInner >= spawnOuter) {
            throw IllegalArgumentException("spawnInner must be less than spawnOuter")
        }
        if (spawnOuter > radius) {
            throw IllegalArgumentException("spawnOuter must not exceed radius")
        }
        if (coreGap < 0.0) {
            throw IllegalArgumentException("coreGap must not be negative")
        }

        val requiredCoreDistance = Math.fma(2.0, radius, coreGap)
        if (!requiredCoreDistance.isFinite()) {
            throw IllegalArgumentException("radius and coreGap produce an unbounded distance")
        }
        if (minimumCoreDistance < requiredCoreDistance) {
            throw IllegalArgumentException(
                "minimumCoreDistance must be at least 2 * radius + coreGap",
            )
        }
    }

    /** Returns the minimum core spacing implied by the radius and configured gap. */
    fun requiredCoreDistance(): Double = Math.fma(2.0, radius, coreGap)

    /** Returns whether a point lies within the combat cylinder, including its edge. */
    fun contains(centerX: Double, centerZ: Double, pointX: Double, pointZ: Double): Boolean =
        horizontalDistance(centerX, centerZ, pointX, pointZ) <= radius

    /** Returns whether a point lies in the inclusive enemy spawn band. */
    fun isInSpawnBand(
        centerX: Double,
        centerZ: Double,
        pointX: Double,
        pointZ: Double,
    ): Boolean {
        val distance = horizontalDistance(centerX, centerZ, pointX, pointZ)
        return distance >= spawnInner && distance <= spawnOuter
    }

    /** Returns whether two cores satisfy the configured horizontal separation. */
    fun coresAreFarEnoughApart(
        firstX: Double,
        firstZ: Double,
        secondX: Double,
        secondZ: Double,
    ): Boolean = horizontalDistance(firstX, firstZ, secondX, secondZ) >= minimumCoreDistance

    companion object {
        @JvmStatic
        fun horizontalDistance(
            firstX: Double,
            firstZ: Double,
            secondX: Double,
            secondZ: Double,
        ): Double {
            requireFinite("firstX", firstX)
            requireFinite("firstZ", firstZ)
            requireFinite("secondX", secondX)
            requireFinite("secondZ", secondZ)
            return Math.hypot(secondX - firstX, secondZ - firstZ)
        }

        private fun requireFinite(name: String, value: Double) {
            if (!value.isFinite()) {
                throw IllegalArgumentException("$name must be finite")
            }
        }
    }
}
