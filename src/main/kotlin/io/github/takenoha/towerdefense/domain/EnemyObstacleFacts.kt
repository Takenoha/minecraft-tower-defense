package io.github.takenoha.towerdefense.domain

import java.util.Objects
import kotlin.jvm.JvmRecord

/** Paper-independent result of a main-thread world snapshot. */
@JvmRecord
data class EnemyObstacleFacts(
    val classification: EnemyObstacleClassification,
    val currentMaterialKey: String,
    val targetMaterialKey: String,
    val withinCombatArea: Boolean,
    val supportAvailable: Boolean,
) {
    init {
        Objects.requireNonNull(classification, "classification")
        requireMaterialKey(currentMaterialKey, "currentMaterialKey")
        requireMaterialKey(targetMaterialKey, "targetMaterialKey")
    }

    companion object {
        /** Returns the fail-closed result used when a Paper world read cannot be completed. */
        @JvmStatic
        fun unavailable(): EnemyObstacleFacts = EnemyObstacleFacts(
            EnemyObstacleClassification.UNAVAILABLE,
            "minecraft:air",
            "minecraft:air",
            false,
            false,
        )

        private fun requireMaterialKey(value: String, name: String): String {
            Objects.requireNonNull(value, name)
            if (value.isBlank()) {
                throw IllegalArgumentException("$name must not be blank")
            }
            return value
        }
    }

    /** Whether this classification matches the physical action requested by the event. */
    fun permits(action: EnemyTerrainActionKind): Boolean {
        Objects.requireNonNull(action, "action")
        return when (classification) {
            EnemyObstacleClassification.BREAKABLE ->
                withinCombatArea && action == EnemyTerrainActionKind.BREAK
            EnemyObstacleClassification.BUILDABLE_GAP ->
                withinCombatArea && supportAvailable && action == EnemyTerrainActionKind.BUILD
            EnemyObstacleClassification.CLEAR,
            EnemyObstacleClassification.PROTECTED,
            EnemyObstacleClassification.UNAVAILABLE,
            -> false
        }
    }

    /** Converts the snapshot into the context consumed by the role-specific planner. */
    fun toPathContext(consecutivePathFailures: Int): EnemyPathContext = when (classification) {
        EnemyObstacleClassification.CLEAR ->
            EnemyPathContext(true, false, false, false, consecutivePathFailures)
        EnemyObstacleClassification.PROTECTED ->
            EnemyPathContext(false, true, false, false, consecutivePathFailures)
        EnemyObstacleClassification.BREAKABLE ->
            EnemyPathContext(false, false, true, false, consecutivePathFailures)
        EnemyObstacleClassification.BUILDABLE_GAP ->
            EnemyPathContext(false, false, false, true, consecutivePathFailures)
        EnemyObstacleClassification.UNAVAILABLE ->
            EnemyPathContext(false, false, false, false, consecutivePathFailures)
    }
}
