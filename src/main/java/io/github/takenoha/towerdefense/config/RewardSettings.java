package io.github.takenoha.towerdefense.config;

import io.github.takenoha.towerdefense.domain.EnemyRole;
import java.time.Duration;

/** Durable reward-delivery timing and stage-reward settings. */
public record RewardSettings(
        int teamQueueRetentionSeconds,
        int researchCrystalBasePerStage,
        int researchCrystalReplayPercent,
        int researchCrystalMinimumQuantity,
        int battleFundsNormalEnemy,
        int battleFundsSpecialEnemy,
        int battleFundsBossEnemy,
        int battleFundsPerWave,
        int defenseShardsNormalEnemy,
        int defenseShardsSpecialEnemy,
        int enhancementCoreDropPercent,
        boolean legacyResourcePaymentsEnabled) {
    public static final int DEFAULT_RESEARCH_CRYSTAL_BASE_PER_STAGE = 100;
    public static final int DEFAULT_RESEARCH_CRYSTAL_REPLAY_PERCENT = 25;
    public static final int DEFAULT_RESEARCH_CRYSTAL_MINIMUM_QUANTITY = 0;
    public static final int DEFAULT_BATTLE_FUNDS_NORMAL_ENEMY = 5;
    public static final int DEFAULT_BATTLE_FUNDS_SPECIAL_ENEMY = 15;
    public static final int DEFAULT_BATTLE_FUNDS_BOSS_ENEMY = 50;
    public static final int DEFAULT_BATTLE_FUNDS_PER_WAVE = 50;
    public static final int DEFAULT_DEFENSE_SHARDS_NORMAL_ENEMY = 1;
    public static final int DEFAULT_DEFENSE_SHARDS_SPECIAL_ENEMY = 2;
    public static final int DEFAULT_ENHANCEMENT_CORE_DROP_PERCENT = 10;

    /** Keeps direct construction source-compatible with the queue-delivery slice. */
    public RewardSettings(int teamQueueRetentionSeconds) {
        this(
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
                true);
    }

    /** Keeps direct construction source-compatible with the research-crystal slice. */
    public RewardSettings(
            int teamQueueRetentionSeconds,
            int researchCrystalBasePerStage,
            int researchCrystalReplayPercent,
            int researchCrystalMinimumQuantity) {
        this(
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
                true);
    }

    /** Default retention used by older direct settings construction and migrated databases. */
    public static RewardSettings defaults() {
        return new RewardSettings(
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
                true);
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

    /** Returns the configured event currency award for one defeated enemy role. */
    public int battleFundsFor(EnemyRole role) {
        return switch (java.util.Objects.requireNonNull(role, "role")) {
            case NORMAL -> battleFundsNormalEnemy;
            case DESTROYER, BUILDER -> battleFundsSpecialEnemy;
            case BOSS -> battleFundsBossEnemy;
        };
    }

    /** Returns the configured shard quantity for one defeated enemy role. */
    public int defenseShardsFor(EnemyRole role) {
        return switch (java.util.Objects.requireNonNull(role, "role")) {
            case NORMAL -> defenseShardsNormalEnemy;
            case DESTROYER, BUILDER, BOSS -> defenseShardsSpecialEnemy;
        };
    }
}
