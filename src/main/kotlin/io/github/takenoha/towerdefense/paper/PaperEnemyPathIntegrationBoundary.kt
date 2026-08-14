package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.domain.EnemyObstacleFacts
import io.github.takenoha.towerdefense.domain.EnemyRole
import io.github.takenoha.towerdefense.runtime.CoreRegistry
import io.github.takenoha.towerdefense.runtime.EnemyAccessPolicy
import io.github.takenoha.towerdefense.runtime.EnemyPathMetrics
import java.util.Objects
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity

/**
 * Read-only Paper integration seam for path inspection and load measurement.
 *
 * All Bukkit reads remain on the main thread. A runtime Paper read failure becomes an unavailable
 * obstacle instead of becoming a terrain action, while the failure and elapsed time remain visible
 * in the in-memory metrics.
 */
class PaperEnemyPathIntegrationBoundary(
    cores: CoreRegistry,
    accessPolicy: EnemyAccessPolicy,
) {
    private val cores: CoreRegistry = Objects.requireNonNull(cores, "cores")
    private val accessPolicy: EnemyAccessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy")

    fun inspect(
        entity: Entity,
        destination: Location,
        role: EnemyRole,
        metrics: EnemyPathMetrics,
    ): EnemyObstacleFacts {
        requireMainThread()
        Objects.requireNonNull(entity, "entity")
        Objects.requireNonNull(destination, "destination")
        Objects.requireNonNull(role, "role")
        Objects.requireNonNull(metrics, "metrics")
        val startedAt = System.nanoTime()
        try {
            return PaperEnemyPathController.inspect(
                entity,
                destination,
                role,
                cores,
                accessPolicy,
            )
        } catch (_: RuntimeException) {
            metrics.recordInspectionFailure()
            return EnemyObstacleFacts.unavailable()
        } finally {
            val elapsed = System.nanoTime() - startedAt
            metrics.recordInspection(Math.max(0L, elapsed))
        }
    }

    private fun requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw IllegalStateException(
                "Paper enemy path integration must run on the main thread",
            )
        }
    }
}
