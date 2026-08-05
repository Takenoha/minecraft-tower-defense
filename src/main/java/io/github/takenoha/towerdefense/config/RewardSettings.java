package io.github.takenoha.towerdefense.config;

import java.time.Duration;

/** Durable reward-delivery timing and stage-reward settings. */
public record RewardSettings(
        int teamQueueRetentionSeconds,
        int researchCrystalBasePerStage,
        int researchCrystalReplayPercent,
        int researchCrystalMinimumQuantity) {
    public static final int DEFAULT_RESEARCH_CRYSTAL_BASE_PER_STAGE = 100;
    public static final int DEFAULT_RESEARCH_CRYSTAL_REPLAY_PERCENT = 25;
    public static final int DEFAULT_RESEARCH_CRYSTAL_MINIMUM_QUANTITY = 0;

    /** Keeps direct construction source-compatible with the queue-delivery slice. */
    public RewardSettings(int teamQueueRetentionSeconds) {
        this(
                teamQueueRetentionSeconds,
                DEFAULT_RESEARCH_CRYSTAL_BASE_PER_STAGE,
                DEFAULT_RESEARCH_CRYSTAL_REPLAY_PERCENT,
                DEFAULT_RESEARCH_CRYSTAL_MINIMUM_QUANTITY);
    }

    /** Default retention used by older direct settings construction and migrated databases. */
    public static RewardSettings defaults() {
        return new RewardSettings(
                7 * 24 * 60 * 60,
                DEFAULT_RESEARCH_CRYSTAL_BASE_PER_STAGE,
                DEFAULT_RESEARCH_CRYSTAL_REPLAY_PERCENT,
                DEFAULT_RESEARCH_CRYSTAL_MINIMUM_QUANTITY);
    }

    public Duration teamQueueRetention() {
        return Duration.ofSeconds(teamQueueRetentionSeconds);
    }

    /**
     * Calculates the one team-scoped crystal batch issued by a successful stage terminal.
     *
     * <p>A first clear pays the full stage value. Replays of the current best level or either
     * of the two immediately preceding levels pay the configured replay percentage. Older
     * replays deliberately pay nothing, matching the requirement's anti-farming boundary.</p>
     */
    public int researchCrystalQuantity(long stageLevel, long highestClearedLevel) {
        if (stageLevel <= 0L) {
            throw new IllegalArgumentException("stageLevel must be positive");
        }
        if (highestClearedLevel < 0L) {
            throw new IllegalArgumentException("highestClearedLevel must be non-negative");
        }
        long fullValue;
        try {
            fullValue = Math.multiplyExact(
                    (long) researchCrystalBasePerStage, stageLevel);
        } catch (ArithmeticException overflow) {
            fullValue = Integer.MAX_VALUE;
        }
        long quantity;
        if (stageLevel > highestClearedLevel) {
            quantity = fullValue;
        } else {
            long distance = highestClearedLevel - stageLevel;
            quantity = distance >= 3L
                    ? 0L
                    : fullValue * researchCrystalReplayPercent / 100L;
        }
        if (quantity <= 0L) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(researchCrystalMinimumQuantity, quantity));
    }
}
