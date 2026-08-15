package io.github.takenoha.towerdefense.config

/** Spatial, participation, and phase timing limits for one defense encounter. */
@JvmRecord
data class CombatSettings(
    val radius: Double,
    val spawnInner: Double,
    val spawnOuter: Double,
    val minimumCoreDistance: Double,
    val coreGap: Double,
    val maxParticipants: Int,
    val countdownSeconds: Int,
    val preparationSeconds: Int,
    val intermissionSeconds: Int,
    val absenceGraceSeconds: Int,
)
