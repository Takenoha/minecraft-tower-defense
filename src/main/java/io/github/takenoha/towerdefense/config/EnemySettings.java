package io.github.takenoha.towerdefense.config;

/** Enemy population, scaling, and movement settings. */
public record EnemySettings(
        int maxAlive,
        int spawnPerTick,
        int basePerWave,
        int addedPerWave,
        double bossHealthMultiplier,
        double moveSpeed,
        double destroyerRatio,
        double builderRatio,
        int towerAttackDamage,
        int towerAttackIntervalTicks,
        double towerAttackRange) {
    public static final double DEFAULT_DESTROYER_RATIO = 0.15d;
    public static final double DEFAULT_BUILDER_RATIO = 0.10d;
    public static final int DEFAULT_TOWER_ATTACK_DAMAGE = 8;
    public static final int DEFAULT_TOWER_ATTACK_INTERVAL_TICKS = 20;
    public static final double DEFAULT_TOWER_ATTACK_RANGE = 2.5d;

    /** Keeps the role-settings constructor source-compatible. */
    public EnemySettings(
            int maxAlive,
            int spawnPerTick,
            int basePerWave,
            int addedPerWave,
            double bossHealthMultiplier,
            double moveSpeed,
            double destroyerRatio,
            double builderRatio) {
        this(
                maxAlive,
                spawnPerTick,
                basePerWave,
                addedPerWave,
                bossHealthMultiplier,
                moveSpeed,
                destroyerRatio,
                builderRatio,
                DEFAULT_TOWER_ATTACK_DAMAGE,
                DEFAULT_TOWER_ATTACK_INTERVAL_TICKS,
                DEFAULT_TOWER_ATTACK_RANGE);
    }

    /** Keeps the pre-role settings constructor source-compatible. */
    public EnemySettings(
            int maxAlive,
            int spawnPerTick,
            int basePerWave,
            int addedPerWave,
            double bossHealthMultiplier,
            double moveSpeed) {
        this(
                maxAlive,
                spawnPerTick,
                basePerWave,
                addedPerWave,
                bossHealthMultiplier,
                moveSpeed,
                DEFAULT_DESTROYER_RATIO,
                DEFAULT_BUILDER_RATIO,
                DEFAULT_TOWER_ATTACK_DAMAGE,
                DEFAULT_TOWER_ATTACK_INTERVAL_TICKS,
                DEFAULT_TOWER_ATTACK_RANGE);
    }
}
