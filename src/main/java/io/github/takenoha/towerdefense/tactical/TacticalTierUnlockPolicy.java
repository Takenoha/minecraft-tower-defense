package io.github.takenoha.towerdefense.tactical;

import java.util.ArrayList;
import java.util.List;

/** Pure threshold policy for automatic Tier 2-5 progress. */
public final class TacticalTierUnlockPolicy {
    private TacticalTierUnlockPolicy() {
    }

    public static int highestProgressTier(int completedWaveCount, int totalWaveCount) {
        requireProgress(completedWaveCount, totalWaveCount);
        int tier = 1;
        if (reached(completedWaveCount, totalWaveCount, 20)) {
            tier = 2;
        }
        if (reached(completedWaveCount, totalWaveCount, 40)) {
            tier = 3;
        }
        if (reached(completedWaveCount, totalWaveCount, 60)) {
            tier = 4;
        }
        if (reached(completedWaveCount, totalWaveCount, 80)) {
            tier = 5;
        }
        return tier;
    }

    public static List<Integer> newlyReachedProgressTiers(
            int previousHighestTier,
            int completedWaveCount,
            int totalWaveCount) {
        if (previousHighestTier < 0 || previousHighestTier > 6) {
            throw new IllegalArgumentException("previousHighestTier must be between 0 and 6");
        }
        int target = highestProgressTier(completedWaveCount, totalWaveCount);
        List<Integer> result = new ArrayList<>();
        for (int tier = Math.max(1, previousHighestTier + 1); tier <= target; tier++) {
            result.add(tier);
        }
        return List.copyOf(result);
    }

    private static boolean reached(int completed, int total, int percent) {
        return (long) completed * 100L >= (long) total * percent;
    }

    private static void requireProgress(int completed, int total) {
        if (total <= 0 || completed < 0 || completed > total) {
            throw new IllegalArgumentException(
                    "completedWaveCount must be between zero and totalWaveCount");
        }
    }
}
