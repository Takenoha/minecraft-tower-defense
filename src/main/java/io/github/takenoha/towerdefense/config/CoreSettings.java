package io.github.takenoha.towerdefense.config;

/** Core health and attack cadence settings for a defense encounter. */
public record CoreSettings(int maxHealth, int damagePerEnemy, int attackIntervalTicks) {
    public static final int DEFAULT_ATTACK_INTERVAL_TICKS = 20;

    /** Keeps direct settings construction source-compatible with the original two fields. */
    public CoreSettings(int maxHealth, int damagePerEnemy) {
        this(maxHealth, damagePerEnemy, DEFAULT_ATTACK_INTERVAL_TICKS);
    }
}
