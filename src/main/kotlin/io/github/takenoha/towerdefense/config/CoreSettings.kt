package io.github.takenoha.towerdefense.config

import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord

/** Core health, attack cadence, and idle repair settings for a defense encounter. */
@JvmRecord
data class CoreSettings(
    val maxHealth: Int,
    val damagePerEnemy: Int,
    val attackIntervalTicks: Int,
    val repairMaterial: String,
    val repairHealthPerUnit: Int,
    val repairMaterialBaseCost: Int,
    val repairShardBaseCost: Int,
    val repairCostPerClearLevel: Int,
    val warningSound: String,
    val warningVolume: Double,
    val warningPitch: Double,
    val warningMinIntervalTicks: Int,
) {
    init {
        requireNotNull(repairMaterial) { "repairMaterial" }
        requireNotNull(warningSound) { "warningSound" }
    }

    /** Keeps direct settings construction source-compatible with the original three fields. */
    constructor(
        maxHealth: Int,
        damagePerEnemy: Int,
        attackIntervalTicks: Int,
    ) : this(
        maxHealth,
        damagePerEnemy,
        attackIntervalTicks,
        DEFAULT_REPAIR_MATERIAL,
        DEFAULT_REPAIR_HEALTH_PER_UNIT,
        DEFAULT_REPAIR_MATERIAL_BASE_COST,
        DEFAULT_REPAIR_SHARD_BASE_COST,
        DEFAULT_REPAIR_COST_PER_CLEAR_LEVEL,
        DEFAULT_WARNING_SOUND,
        DEFAULT_WARNING_VOLUME,
        DEFAULT_WARNING_PITCH,
        DEFAULT_WARNING_MIN_INTERVAL_TICKS,
    )

    /** Keeps direct settings construction source-compatible with the original two fields. */
    constructor(
        maxHealth: Int,
        damagePerEnemy: Int,
    ) : this(maxHealth, damagePerEnemy, DEFAULT_ATTACK_INTERVAL_TICKS)

    /** Keeps direct settings construction source-compatible with the repair-economy fields. */
    constructor(
        maxHealth: Int,
        damagePerEnemy: Int,
        attackIntervalTicks: Int,
        repairMaterial: String,
        repairHealthPerUnit: Int,
        repairMaterialBaseCost: Int,
        repairShardBaseCost: Int,
        repairCostPerClearLevel: Int,
    ) : this(
        maxHealth,
        damagePerEnemy,
        attackIntervalTicks,
        repairMaterial,
        repairHealthPerUnit,
        repairMaterialBaseCost,
        repairShardBaseCost,
        repairCostPerClearLevel,
        DEFAULT_WARNING_SOUND,
        DEFAULT_WARNING_VOLUME,
        DEFAULT_WARNING_PITCH,
        DEFAULT_WARNING_MIN_INTERVAL_TICKS,
    )

    companion object {
        @JvmField
        val DEFAULT_ATTACK_INTERVAL_TICKS: Int = 20
        @JvmField
        val DEFAULT_REPAIR_MATERIAL: String = "IRON_INGOT"
        @JvmField
        val DEFAULT_REPAIR_HEALTH_PER_UNIT: Int = 100
        @JvmField
        val DEFAULT_REPAIR_MATERIAL_BASE_COST: Int = 1
        @JvmField
        val DEFAULT_REPAIR_SHARD_BASE_COST: Int = 1
        @JvmField
        val DEFAULT_REPAIR_COST_PER_CLEAR_LEVEL: Int = 1
        @JvmField
        val DEFAULT_WARNING_SOUND: String = "ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR"
        @JvmField
        val DEFAULT_WARNING_VOLUME: Double = 1.0
        @JvmField
        val DEFAULT_WARNING_PITCH: Double = 1.0
        @JvmField
        val DEFAULT_WARNING_MIN_INTERVAL_TICKS: Int = 10
    }
}
