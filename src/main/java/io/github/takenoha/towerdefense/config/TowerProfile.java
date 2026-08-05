package io.github.takenoha.towerdefense.config;

/** Configurable combat profile for one of the specialist tower types. */
public record TowerProfile(
        int damage,
        double range,
        int attackIntervalTicks,
        double areaRadius,
        double slowPercent,
        int slowDurationTicks,
        int chainCount,
        double chainRadius,
        double supportRadius,
        double supportDamageMultiplier,
        double supportSpeedMultiplier,
        double supportRangeMultiplier,
        int supportStackLimit,
        int burnDurationTicks) {

    public static TowerProfile frostDefaults() {
        return new TowerProfile(2, 12.0d, 30, 0.0d, 0.35d, 50, 0, 0.0d,
                0.0d, 1.0d, 1.0d, 1.0d, 0, 0);
    }

    public static TowerProfile lightningDefaults() {
        return new TowerProfile(6, 18.0d, 35, 0.0d, 0.0d, 0, 3, 5.0d,
                0.0d, 1.0d, 1.0d, 1.0d, 0, 0);
    }

    public static TowerProfile supportDefaults() {
        return new TowerProfile(1, 10.0d, 40, 0.0d, 0.0d, 0, 0, 0.0d,
                8.0d, 1.25d, 0.80d, 1.15d, 2, 0);
    }

    public static TowerProfile sniperDefaults() {
        return new TowerProfile(18, 32.0d, 60, 0.0d, 0.0d, 0, 0, 0.0d,
                0.0d, 1.0d, 1.0d, 1.0d, 0, 0);
    }

    public static TowerProfile flameDefaults() {
        return new TowerProfile(3, 15.0d, 25, 3.0d, 0.0d, 0, 0, 0.0d,
                0.0d, 1.0d, 1.0d, 1.0d, 0, 80);
    }
}
