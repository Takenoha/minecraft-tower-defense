package io.github.takenoha.towerdefense.config;

/** Enemy population, scaling, and movement settings. */
public record EnemySettings(
        int maxAlive,
        int spawnPerTick,
        int basePerWave,
        int addedPerWave,
        double bossHealthMultiplier,
        double moveSpeed) {
}
