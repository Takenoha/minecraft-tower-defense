package io.github.takenoha.towerdefense.config

import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord

/** Enemy population, scaling, and movement settings. */
@JvmRecord
data class EnemySettings(
    val maxAlive: Int,
    val spawnPerTick: Int,
    val basePerWave: Int,
    val addedPerWave: Int,
    val bossHealthMultiplier: Double,
    val moveSpeed: Double,
    val destroyerRatio: Double,
    val builderRatio: Double,
    val speedsterRatio: Double,
    val rangedRatio: Double,
    val heavyRatio: Double,
    val supportRatio: Double,
    val supportRadius: Double,
    val supportHealAmount: Double,
    val supportCooldownTicks: Int,
    val supportSpeedMultiplier: Double,
    val supportSpeedDurationTicks: Int,
    val towerAttackDamage: Int,
    val towerAttackIntervalTicks: Int,
    val towerAttackRange: Double,
) {
    /** Keeps the role-settings constructor source-compatible. */
    constructor(
        maxAlive: Int,
        spawnPerTick: Int,
        basePerWave: Int,
        addedPerWave: Int,
        bossHealthMultiplier: Double,
        moveSpeed: Double,
        destroyerRatio: Double,
        builderRatio: Double,
        towerAttackDamage: Int,
        towerAttackIntervalTicks: Int,
        towerAttackRange: Double,
    ) : this(
        maxAlive,
        spawnPerTick,
        basePerWave,
        addedPerWave,
        bossHealthMultiplier,
        moveSpeed,
        destroyerRatio,
        builderRatio,
        DEFAULT_SPEEDSTER_RATIO,
        DEFAULT_RANGED_RATIO,
        DEFAULT_HEAVY_RATIO,
        DEFAULT_SUPPORT_RATIO,
        DEFAULT_SUPPORT_RADIUS,
        DEFAULT_SUPPORT_HEAL_AMOUNT,
        DEFAULT_SUPPORT_COOLDOWN_TICKS,
        DEFAULT_SUPPORT_SPEED_MULTIPLIER,
        DEFAULT_SUPPORT_SPEED_DURATION_TICKS,
        towerAttackDamage,
        towerAttackIntervalTicks,
        towerAttackRange,
    )

    /** Keeps the role-settings constructor source-compatible. */
    constructor(
        maxAlive: Int,
        spawnPerTick: Int,
        basePerWave: Int,
        addedPerWave: Int,
        bossHealthMultiplier: Double,
        moveSpeed: Double,
        destroyerRatio: Double,
        builderRatio: Double,
    ) : this(
        maxAlive,
        spawnPerTick,
        basePerWave,
        addedPerWave,
        bossHealthMultiplier,
        moveSpeed,
        destroyerRatio,
        builderRatio,
        DEFAULT_SPEEDSTER_RATIO,
        DEFAULT_RANGED_RATIO,
        DEFAULT_HEAVY_RATIO,
        DEFAULT_SUPPORT_RATIO,
        DEFAULT_SUPPORT_RADIUS,
        DEFAULT_SUPPORT_HEAL_AMOUNT,
        DEFAULT_SUPPORT_COOLDOWN_TICKS,
        DEFAULT_SUPPORT_SPEED_MULTIPLIER,
        DEFAULT_SUPPORT_SPEED_DURATION_TICKS,
        DEFAULT_TOWER_ATTACK_DAMAGE,
        DEFAULT_TOWER_ATTACK_INTERVAL_TICKS,
        DEFAULT_TOWER_ATTACK_RANGE,
    )

    /** Keeps the pre-role settings constructor source-compatible. */
    constructor(
        maxAlive: Int,
        spawnPerTick: Int,
        basePerWave: Int,
        addedPerWave: Int,
        bossHealthMultiplier: Double,
        moveSpeed: Double,
    ) : this(
        maxAlive,
        spawnPerTick,
        basePerWave,
        addedPerWave,
        bossHealthMultiplier,
        moveSpeed,
        DEFAULT_DESTROYER_RATIO,
        DEFAULT_BUILDER_RATIO,
        DEFAULT_SPEEDSTER_RATIO,
        DEFAULT_RANGED_RATIO,
        DEFAULT_HEAVY_RATIO,
        DEFAULT_SUPPORT_RATIO,
        DEFAULT_SUPPORT_RADIUS,
        DEFAULT_SUPPORT_HEAL_AMOUNT,
        DEFAULT_SUPPORT_COOLDOWN_TICKS,
        DEFAULT_SUPPORT_SPEED_MULTIPLIER,
        DEFAULT_SUPPORT_SPEED_DURATION_TICKS,
        DEFAULT_TOWER_ATTACK_DAMAGE,
        DEFAULT_TOWER_ATTACK_INTERVAL_TICKS,
        DEFAULT_TOWER_ATTACK_RANGE,
    )

    companion object {
        @JvmField
        val DEFAULT_DESTROYER_RATIO: Double = 0.15

        @JvmField
        val DEFAULT_BUILDER_RATIO: Double = 0.10

        @JvmField
        val DEFAULT_SPEEDSTER_RATIO: Double = 0.10

        @JvmField
        val DEFAULT_RANGED_RATIO: Double = 0.10

        @JvmField
        val DEFAULT_HEAVY_RATIO: Double = 0.05

        @JvmField
        val DEFAULT_SUPPORT_RATIO: Double = 0.05

        @JvmField
        val DEFAULT_SUPPORT_RADIUS: Double = 8.0

        @JvmField
        val DEFAULT_SUPPORT_HEAL_AMOUNT: Double = 4.0

        @JvmField
        val DEFAULT_SUPPORT_COOLDOWN_TICKS: Int = 100

        @JvmField
        val DEFAULT_SUPPORT_SPEED_MULTIPLIER: Double = 1.15

        @JvmField
        val DEFAULT_SUPPORT_SPEED_DURATION_TICKS: Int = 60

        @JvmField
        val DEFAULT_TOWER_ATTACK_DAMAGE: Int = 8

        @JvmField
        val DEFAULT_TOWER_ATTACK_INTERVAL_TICKS: Int = 20

        @JvmField
        val DEFAULT_TOWER_ATTACK_RANGE: Double = 2.5
    }
}
