package io.github.takenoha.towerdefense.config;

import io.github.takenoha.towerdefense.domain.TowerType;

/**
 * Shared tower limits and the first two tower types' combat values.
 *
 * <p>The remaining tower-specific values are intentionally added in their own slices so a
 * future balance change cannot silently change the already-persisted tower identity.</p>
 */
public record TowerSettings(
        int baseLimit,
        int limitIncrement,
        int hardCap,
        int arrowDamage,
        double arrowRange,
        int arrowAttackIntervalTicks,
        int cannonDamage,
        double cannonRange,
        int cannonAttackIntervalTicks,
        double cannonSplashRadius) {
    public static final int DEFAULT_BASE_LIMIT = 8;
    public static final int DEFAULT_LIMIT_INCREMENT = 2;
    public static final int DEFAULT_HARD_CAP = 40;
    public static final int DEFAULT_ARROW_DAMAGE = 4;
    public static final double DEFAULT_ARROW_RANGE = 16.0d;
    public static final int DEFAULT_ARROW_ATTACK_INTERVAL_TICKS = 20;
    public static final int DEFAULT_CANNON_DAMAGE = 8;
    public static final double DEFAULT_CANNON_RANGE = 14.0d;
    public static final int DEFAULT_CANNON_ATTACK_INTERVAL_TICKS = 40;
    public static final double DEFAULT_CANNON_SPLASH_RADIUS = 2.5d;

    /** Backward-compatible constructor for callers that only configure the Arrow tower. */
    public TowerSettings(
            int baseLimit,
            int limitIncrement,
            int hardCap,
            int arrowDamage,
            double arrowRange,
            int arrowAttackIntervalTicks) {
        this(
                baseLimit,
                limitIncrement,
                hardCap,
                arrowDamage,
                arrowRange,
                arrowAttackIntervalTicks,
                DEFAULT_CANNON_DAMAGE,
                DEFAULT_CANNON_RANGE,
                DEFAULT_CANNON_ATTACK_INTERVAL_TICKS,
                DEFAULT_CANNON_SPLASH_RADIUS);
    }

    public static TowerSettings defaults() {
        return new TowerSettings(
                DEFAULT_BASE_LIMIT,
                DEFAULT_LIMIT_INCREMENT,
                DEFAULT_HARD_CAP,
                DEFAULT_ARROW_DAMAGE,
                DEFAULT_ARROW_RANGE,
                DEFAULT_ARROW_ATTACK_INTERVAL_TICKS,
                DEFAULT_CANNON_DAMAGE,
                DEFAULT_CANNON_RANGE,
                DEFAULT_CANNON_ATTACK_INTERVAL_TICKS,
                DEFAULT_CANNON_SPLASH_RADIUS);
    }

    public double rangeFor(TowerType type) {
        return switch (java.util.Objects.requireNonNull(type, "type")) {
            case ARROW -> arrowRange;
            case CANNON -> cannonRange;
        };
    }

    public int damageFor(TowerType type) {
        return switch (java.util.Objects.requireNonNull(type, "type")) {
            case ARROW -> arrowDamage;
            case CANNON -> cannonDamage;
        };
    }

    public int attackIntervalTicksFor(TowerType type) {
        return switch (java.util.Objects.requireNonNull(type, "type")) {
            case ARROW -> arrowAttackIntervalTicks;
            case CANNON -> cannonAttackIntervalTicks;
        };
    }

    /** Returns the bounded tower count allowed by the team's highest cleared level. */
    public int limitFor(long highestClearedLevel) {
        if (highestClearedLevel < 0L) {
            throw new IllegalArgumentException("highestClearedLevel must not be negative");
        }
        long calculated;
        try {
            calculated = Math.addExact(
                    baseLimit,
                    Math.multiplyExact((long) limitIncrement, highestClearedLevel));
        } catch (ArithmeticException overflow) {
            return hardCap;
        }
        return (int) Math.min((long) hardCap, calculated);
    }
}
