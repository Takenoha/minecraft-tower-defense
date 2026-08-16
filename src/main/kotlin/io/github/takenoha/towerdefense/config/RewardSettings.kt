package io.github.takenoha.towerdefense.config

import io.github.takenoha.towerdefense.domain.EnemyRole
import java.time.Duration
import java.util.Objects
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord

/** Durable reward-delivery timing and stage-reward settings. */
@JvmRecord
data class RewardSettings(
    val teamQueueRetentionSeconds: Int,
    val researchCrystalBasePerStage: Int,
    val researchCrystalReplayPercent: Int,
    val researchCrystalMinimumQuantity: Int,
    val battleFundsNormalEnemy: Int,
    val battleFundsSpecialEnemy: Int,
    val battleFundsBossEnemy: Int,
    val battleFundsPerWave: Int,
    val defenseShardsNormalEnemy: Int,
    val defenseShardsSpecialEnemy: Int,
    val enhancementCoreDropPercent: Int,
    val legacyResourcePaymentsEnabled: Boolean,
) {
    /** Keeps direct construction source-compatible with the queue-delivery slice. */
    constructor(teamQueueRetentionSeconds: Int) : this(
        teamQueueRetentionSeconds,
        DEFAULT_RESEARCH_CRYSTAL_BASE_PER_STAGE,
        DEFAULT_RESEARCH_CRYSTAL_REPLAY_PERCENT,
        DEFAULT_RESEARCH_CRYSTAL_MINIMUM_QUANTITY,
        DEFAULT_BATTLE_FUNDS_NORMAL_ENEMY,
        DEFAULT_BATTLE_FUNDS_SPECIAL_ENEMY,
        DEFAULT_BATTLE_FUNDS_BOSS_ENEMY,
        DEFAULT_BATTLE_FUNDS_PER_WAVE,
        DEFAULT_DEFENSE_SHARDS_NORMAL_ENEMY,
        DEFAULT_DEFENSE_SHARDS_SPECIAL_ENEMY,
        DEFAULT_ENHANCEMENT_CORE_DROP_PERCENT,
        true,
    )

    /** Keeps direct construction source-compatible with the research-crystal slice. */
    constructor(
        teamQueueRetentionSeconds: Int,
        researchCrystalBasePerStage: Int,
        researchCrystalReplayPercent: Int,
        researchCrystalMinimumQuantity: Int,
    ) : this(
        teamQueueRetentionSeconds,
        researchCrystalBasePerStage,
        researchCrystalReplayPercent,
        researchCrystalMinimumQuantity,
        DEFAULT_BATTLE_FUNDS_NORMAL_ENEMY,
        DEFAULT_BATTLE_FUNDS_SPECIAL_ENEMY,
        DEFAULT_BATTLE_FUNDS_BOSS_ENEMY,
        DEFAULT_BATTLE_FUNDS_PER_WAVE,
        DEFAULT_DEFENSE_SHARDS_NORMAL_ENEMY,
        DEFAULT_DEFENSE_SHARDS_SPECIAL_ENEMY,
        DEFAULT_ENHANCEMENT_CORE_DROP_PERCENT,
        true,
    )

    companion object {
        @JvmField
        val DEFAULT_RESEARCH_CRYSTAL_BASE_PER_STAGE: Int = 100

        @JvmField
        val DEFAULT_RESEARCH_CRYSTAL_REPLAY_PERCENT: Int = 25

        @JvmField
        val DEFAULT_RESEARCH_CRYSTAL_MINIMUM_QUANTITY: Int = 0

        @JvmField
        val DEFAULT_BATTLE_FUNDS_NORMAL_ENEMY: Int = 5

        @JvmField
        val DEFAULT_BATTLE_FUNDS_SPECIAL_ENEMY: Int = 15

        @JvmField
        val DEFAULT_BATTLE_FUNDS_BOSS_ENEMY: Int = 50

        @JvmField
        val DEFAULT_BATTLE_FUNDS_PER_WAVE: Int = 50

        @JvmField
        val DEFAULT_DEFENSE_SHARDS_NORMAL_ENEMY: Int = 1

        @JvmField
        val DEFAULT_DEFENSE_SHARDS_SPECIAL_ENEMY: Int = 2

        @JvmField
        val DEFAULT_ENHANCEMENT_CORE_DROP_PERCENT: Int = 10

        /** Default retention used by older direct settings construction and migrated databases. */
        @JvmStatic
        fun defaults(): RewardSettings = RewardSettings(
            7 * 24 * 60 * 60,
            DEFAULT_RESEARCH_CRYSTAL_BASE_PER_STAGE,
            DEFAULT_RESEARCH_CRYSTAL_REPLAY_PERCENT,
            DEFAULT_RESEARCH_CRYSTAL_MINIMUM_QUANTITY,
            DEFAULT_BATTLE_FUNDS_NORMAL_ENEMY,
            DEFAULT_BATTLE_FUNDS_SPECIAL_ENEMY,
            DEFAULT_BATTLE_FUNDS_BOSS_ENEMY,
            DEFAULT_BATTLE_FUNDS_PER_WAVE,
            DEFAULT_DEFENSE_SHARDS_NORMAL_ENEMY,
            DEFAULT_DEFENSE_SHARDS_SPECIAL_ENEMY,
            DEFAULT_ENHANCEMENT_CORE_DROP_PERCENT,
            true,
        )
    }

    fun teamQueueRetention(): Duration = Duration.ofSeconds(teamQueueRetentionSeconds.toLong())

    /**
     * Calculates the one team-scoped crystal batch issued by a successful stage terminal.
     */
    fun researchCrystalQuantity(stageLevel: Long, highestClearedLevel: Long): Int {
        if (stageLevel <= 0L) {
            throw IllegalArgumentException("stageLevel must be positive")
        }
        if (highestClearedLevel < 0L) {
            throw IllegalArgumentException("highestClearedLevel must be non-negative")
        }
        val fullValue = try {
            Math.multiplyExact(researchCrystalBasePerStage.toLong(), stageLevel)
        } catch (overflow: ArithmeticException) {
            Int.MAX_VALUE.toLong()
        }
        val quantity = if (stageLevel > highestClearedLevel) {
            fullValue
        } else {
            val distance = highestClearedLevel - stageLevel
            if (distance >= 3L) {
                0L
            } else {
                fullValue * researchCrystalReplayPercent / 100L
            }
        }
        if (quantity <= 0L) {
            return 0
        }
        return minOf(
            Int.MAX_VALUE.toLong(),
            maxOf(researchCrystalMinimumQuantity.toLong(), quantity),
        ).toInt()
    }

    /** Applies an immutable event-start reward multiplier to the stage-clear award. */
    fun researchCrystalQuantity(
        stageLevel: Long,
        highestClearedLevel: Long,
        rewardMultiplier: Double,
    ): Int {
        require(rewardMultiplier.isFinite() && rewardMultiplier > 0.0) {
            "rewardMultiplier must be finite and > 0"
        }
        val baseQuantity = researchCrystalQuantity(stageLevel, highestClearedLevel)
        if (baseQuantity == 0 || rewardMultiplier == 1.0) {
            return baseQuantity
        }
        val scaled = Math.ceil(baseQuantity.toDouble() * rewardMultiplier)
        if (!scaled.isFinite()) {
            throw IllegalArgumentException("scaled research crystal quantity overflows")
        }
        return minOf(Int.MAX_VALUE.toDouble(), scaled).toInt()
    }

    /** Returns the configured event currency award for one defeated enemy role. */
    fun battleFundsFor(role: EnemyRole): Int = when (Objects.requireNonNull(role, "role")) {
        EnemyRole.NORMAL -> battleFundsNormalEnemy
        EnemyRole.DESTROYER,
        EnemyRole.BUILDER,
        EnemyRole.SPEEDSTER,
        EnemyRole.RANGED,
        EnemyRole.HEAVY,
        EnemyRole.SUPPORT -> battleFundsSpecialEnemy
        EnemyRole.BOSS -> battleFundsBossEnemy
    }

    /** Returns the configured shard quantity for one defeated enemy role. */
    fun defenseShardsFor(role: EnemyRole): Int = when (Objects.requireNonNull(role, "role")) {
        EnemyRole.NORMAL -> defenseShardsNormalEnemy
        EnemyRole.DESTROYER,
        EnemyRole.BUILDER,
        EnemyRole.SPEEDSTER,
        EnemyRole.RANGED,
        EnemyRole.HEAVY,
        EnemyRole.SUPPORT,
        EnemyRole.BOSS -> defenseShardsSpecialEnemy
    }
}
