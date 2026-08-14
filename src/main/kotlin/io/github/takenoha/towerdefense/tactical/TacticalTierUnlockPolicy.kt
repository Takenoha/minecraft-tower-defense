package io.github.takenoha.towerdefense.tactical

import java.util.ArrayList

/** Pure threshold policy for automatic Tier 2-5 progress. */
class TacticalTierUnlockPolicy private constructor() {
    companion object {
        @JvmStatic
        fun highestProgressTier(completedWaveCount: Int, totalWaveCount: Int): Int {
            requireProgress(completedWaveCount, totalWaveCount)
            var tier = 1
            if (reached(completedWaveCount, totalWaveCount, 20)) {
                tier = 2
            }
            if (reached(completedWaveCount, totalWaveCount, 40)) {
                tier = 3
            }
            if (reached(completedWaveCount, totalWaveCount, 60)) {
                tier = 4
            }
            if (reached(completedWaveCount, totalWaveCount, 80)) {
                tier = 5
            }
            return tier
        }

        @JvmStatic
        fun newlyReachedProgressTiers(
            previousHighestTier: Int,
            completedWaveCount: Int,
            totalWaveCount: Int,
        ): List<Int> {
            if (previousHighestTier < 0 || previousHighestTier > 6) {
                throw IllegalArgumentException("previousHighestTier must be between 0 and 6")
            }
            val target = highestProgressTier(completedWaveCount, totalWaveCount)
            val result = ArrayList<Int>()
            for (tier in maxOf(1, previousHighestTier + 1)..target) {
                result.add(tier)
            }
            return java.util.List.copyOf(result)
        }

        private fun reached(completed: Int, total: Int, percent: Int): Boolean =
            completed.toLong() * 100L >= total.toLong() * percent.toLong()

        private fun requireProgress(completed: Int, total: Int) {
            if (total <= 0 || completed < 0 || completed > total) {
                throw IllegalArgumentException(
                    "completedWaveCount must be between zero and totalWaveCount",
                )
            }
        }
    }
}
