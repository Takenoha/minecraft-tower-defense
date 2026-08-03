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
        double builderRatio) {
    public static final double DEFAULT_DESTROYER_RATIO = 0.15d;
    public static final double DEFAULT_BUILDER_RATIO = 0.10d;

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
                DEFAULT_BUILDER_RATIO);
    }
}
