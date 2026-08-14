package io.github.takenoha.towerdefense.domain

import io.github.takenoha.towerdefense.config.CoreSettings
import java.math.BigInteger
import kotlin.jvm.JvmRecord

/**
 * Immutable quote for restoring a damaged core to full health.
 *
 * The quote is deliberately calculated outside Bukkit so the same arithmetic can be used by
 * the GUI, persistence tests, and a future admin audit command. One repair unit covers a fixed
 * amount of missing HP; both material and shard costs scale with the team's highest clear level.
 */
@JvmRecord
data class CoreRepairCost(
    val repairAmount: Long,
    val repairUnits: Long,
    val vanillaMaterialAmount: Long,
    val defenseShardAmount: Long,
    val highestClearedLevel: Long,
) {
    init {
        requirePositive(repairAmount, "repairAmount")
        requirePositive(repairUnits, "repairUnits")
        requirePositive(vanillaMaterialAmount, "vanillaMaterialAmount")
        requirePositive(defenseShardAmount, "defenseShardAmount")
        if (highestClearedLevel < 0L) {
            throw IllegalArgumentException("highestClearedLevel must be non-negative")
        }
    }

    companion object {
        @JvmStatic
        fun forMissing(
            missingHitPoints: Long,
            highestClearedLevel: Long,
            settings: CoreSettings?,
        ): CoreRepairCost {
            if (settings == null) {
                throw NullPointerException("settings")
            }
            val nonNullSettings: CoreSettings = settings
            if (missingHitPoints <= 0L) {
                throw IllegalArgumentException("missingHitPoints must be positive")
            }
            if (highestClearedLevel < 0L) {
                throw IllegalArgumentException("highestClearedLevel must be non-negative")
            }
            val repairUnits = ceilDiv(missingHitPoints, nonNullSettings.repairHealthPerUnit)
            val levelCost = BigInteger.valueOf(highestClearedLevel)
                .multiply(BigInteger.valueOf(nonNullSettings.repairCostPerClearLevel.toLong()))
            val materialPerUnit = levelCost.add(
                BigInteger.valueOf(nonNullSettings.repairMaterialBaseCost.toLong()),
            )
            val shardsPerUnit = levelCost.add(
                BigInteger.valueOf(nonNullSettings.repairShardBaseCost.toLong()),
            )
            return CoreRepairCost(
                missingHitPoints,
                repairUnits,
                multiplyToLong(materialPerUnit, repairUnits, "vanillaMaterialAmount"),
                multiplyToLong(shardsPerUnit, repairUnits, "defenseShardAmount"),
                highestClearedLevel,
            )
        }

        private fun ceilDiv(dividend: Long, divisor: Int): Long {
            if (divisor <= 0) {
                throw IllegalArgumentException("repair health unit must be positive")
            }
            val quotient = dividend / divisor.toLong()
            return if (dividend % divisor.toLong() == 0L) quotient else Math.addExact(quotient, 1L)
        }

        private fun multiplyToLong(left: BigInteger, right: Long, name: String): Long {
            return try {
                left.multiply(BigInteger.valueOf(right)).longValueExact()
            } catch (overflow: ArithmeticException) {
                throw IllegalArgumentException("$name exceeds Long.MAX_VALUE", overflow)
            }
        }

        private fun requirePositive(value: Long, name: String) {
            if (value <= 0L) {
                throw IllegalArgumentException("$name must be positive")
            }
        }
    }
}
