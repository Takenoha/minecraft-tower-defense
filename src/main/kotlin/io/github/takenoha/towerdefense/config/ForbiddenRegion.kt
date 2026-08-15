package io.github.takenoha.towerdefense.config

import java.util.Locale
import kotlin.jvm.JvmRecord

/** A horizontal, inclusive region in which a defense combat area may not be placed. */
@JvmRecord
data class ForbiddenRegion(
    val worldName: String?,
    val minX: Double,
    val minZ: Double,
    val maxX: Double,
    val maxZ: Double,
) {
    /** Returns whether the point is inside this region, including its boundary. */
    fun contains(candidateWorld: String?, x: Double, z: Double): Boolean =
        sameWorld(candidateWorld) &&
            x >= minX &&
            x <= maxX &&
            z >= minZ &&
            z <= maxZ

    /** Returns whether this region intersects a horizontal combat circle. */
    fun intersectsCircle(
        candidateWorld: String?,
        centerX: Double,
        centerZ: Double,
        radius: Double,
    ): Boolean {
        if (!sameWorld(candidateWorld)) {
            return false
        }
        val closestX = maxOf(minX, minOf(centerX, maxX))
        val closestZ = maxOf(minZ, minOf(centerZ, maxZ))
        val distanceX = centerX - closestX
        val distanceZ = centerZ - closestZ
        return Math.fma(distanceX, distanceX, distanceZ * distanceZ) <= radius * radius
    }

    private fun sameWorld(candidateWorld: String?): Boolean =
        candidateWorld != null &&
            worldName != null &&
            worldName.lowercase(Locale.ROOT) == candidateWorld.lowercase(Locale.ROOT)
}
