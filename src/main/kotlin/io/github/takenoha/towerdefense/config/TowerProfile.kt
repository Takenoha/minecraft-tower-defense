package io.github.takenoha.towerdefense.config

import kotlin.jvm.JvmRecord

/** Configurable combat profile for one of the specialist tower types. */
@JvmRecord
data class TowerProfile(
    val damage: Int,
    val range: Double,
    val attackIntervalTicks: Int,
    val areaRadius: Double,
    val slowPercent: Double,
    val slowDurationTicks: Int,
    val chainCount: Int,
    val chainRadius: Double,
    val supportRadius: Double,
    val supportDamageMultiplier: Double,
    val supportSpeedMultiplier: Double,
    val supportRangeMultiplier: Double,
    val supportStackLimit: Int,
    val burnDurationTicks: Int,
) {
    companion object {
        @JvmStatic
        fun frostDefaults(): TowerProfile = TowerProfile(
            2, 12.0, 30, 0.0, 0.35, 50, 0, 0.0,
            0.0, 1.0, 1.0, 1.0, 0, 0,
        )

        @JvmStatic
        fun lightningDefaults(): TowerProfile = TowerProfile(
            6, 18.0, 35, 0.0, 0.0, 0, 3, 5.0,
            0.0, 1.0, 1.0, 1.0, 0, 0,
        )

        @JvmStatic
        fun supportDefaults(): TowerProfile = TowerProfile(
            1, 10.0, 40, 0.0, 0.0, 0, 0, 0.0,
            8.0, 1.25, 0.80, 1.15, 2, 0,
        )

        @JvmStatic
        fun sniperDefaults(): TowerProfile = TowerProfile(
            18, 32.0, 60, 0.0, 0.0, 0, 0, 0.0,
            0.0, 1.0, 1.0, 1.0, 0, 0,
        )

        @JvmStatic
        fun flameDefaults(): TowerProfile = TowerProfile(
            3, 15.0, 25, 3.0, 0.0, 0, 0, 0.0,
            0.0, 1.0, 1.0, 1.0, 0, 80,
        )
    }
}
