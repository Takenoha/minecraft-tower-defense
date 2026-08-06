package io.github.takenoha.towerdefense.config;

import io.github.takenoha.towerdefense.domain.TowerType;
import java.util.Map;
import java.util.Objects;

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
        int towerMaximumHitPoints,
        int arrowDamage,
        double arrowRange,
        int arrowAttackIntervalTicks,
        int cannonDamage,
        double cannonRange,
        int cannonAttackIntervalTicks,
        double cannonSplashRadius,
        int individualUpgradeBaseShardCost,
        int individualUpgradeBaseCoreCost,
        int individualUpgradeShardCostPerLevel,
        int individualUpgradeCoreCostPerLevel,
        int researchBaseCost,
        int researchCostPerLevel,
        int battleBoostBaseCost,
        int battleBoostCostPerLevel,
        double battleBoostPowerMultiplier,
        double battleBoostSpeedMultiplier,
        double battleBoostRangeMultiplier,
        int battleBoostStackLimit,
        int battleRepairFundsPerHealth,
        int battleRepairHealthPerPurchase,
        Map<TowerType, TowerProfile> specialistProfiles) {
    public static final int DEFAULT_BASE_LIMIT = 8;
    public static final int DEFAULT_LIMIT_INCREMENT = 2;
    public static final int DEFAULT_HARD_CAP = 40;
    public static final int DEFAULT_TOWER_MAXIMUM_HIT_POINTS = 100;
    public static final int DEFAULT_ARROW_DAMAGE = 4;
    public static final double DEFAULT_ARROW_RANGE = 16.0d;
    public static final int DEFAULT_ARROW_ATTACK_INTERVAL_TICKS = 20;
    public static final int DEFAULT_CANNON_DAMAGE = 8;
    public static final double DEFAULT_CANNON_RANGE = 14.0d;
    public static final int DEFAULT_CANNON_ATTACK_INTERVAL_TICKS = 40;
    public static final double DEFAULT_CANNON_SPLASH_RADIUS = 2.5d;
    public static final int DEFAULT_INDIVIDUAL_UPGRADE_BASE_SHARD_COST = 2;
    public static final int DEFAULT_INDIVIDUAL_UPGRADE_BASE_CORE_COST = 1;
    public static final int DEFAULT_INDIVIDUAL_UPGRADE_SHARD_COST_PER_LEVEL = 1;
    public static final int DEFAULT_INDIVIDUAL_UPGRADE_CORE_COST_PER_LEVEL = 1;
    public static final int DEFAULT_RESEARCH_BASE_COST = 10;
    public static final int DEFAULT_RESEARCH_COST_PER_LEVEL = 5;
    public static final int DEFAULT_BATTLE_BOOST_BASE_COST = 25;
    public static final int DEFAULT_BATTLE_BOOST_COST_PER_LEVEL = 15;
    public static final double DEFAULT_BATTLE_BOOST_POWER_MULTIPLIER = 1.20d;
    public static final double DEFAULT_BATTLE_BOOST_SPEED_MULTIPLIER = 0.85d;
    public static final double DEFAULT_BATTLE_BOOST_RANGE_MULTIPLIER = 1.15d;
    public static final int DEFAULT_BATTLE_BOOST_STACK_LIMIT = 3;
    public static final int DEFAULT_BATTLE_REPAIR_FUNDS_PER_HEALTH = 1;
    public static final int DEFAULT_BATTLE_REPAIR_HEALTH_PER_PURCHASE = 10;

    public TowerSettings {
        specialistProfiles = Map.copyOf(
                Objects.requireNonNull(specialistProfiles, "specialistProfiles"));
        for (TowerType type : TowerType.values()) {
            if (type != TowerType.ARROW && type != TowerType.CANNON
                    && !specialistProfiles.containsKey(type)) {
                throw new IllegalArgumentException(
                        "missing tower profile for " + type.id());
            }
        }
    }

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
                DEFAULT_TOWER_MAXIMUM_HIT_POINTS,
                arrowDamage,
                arrowRange,
                arrowAttackIntervalTicks,
                DEFAULT_CANNON_DAMAGE,
                DEFAULT_CANNON_RANGE,
                DEFAULT_CANNON_ATTACK_INTERVAL_TICKS,
                DEFAULT_CANNON_SPLASH_RADIUS,
                DEFAULT_INDIVIDUAL_UPGRADE_BASE_SHARD_COST,
                DEFAULT_INDIVIDUAL_UPGRADE_BASE_CORE_COST,
                DEFAULT_INDIVIDUAL_UPGRADE_SHARD_COST_PER_LEVEL,
                DEFAULT_INDIVIDUAL_UPGRADE_CORE_COST_PER_LEVEL,
                DEFAULT_RESEARCH_BASE_COST,
                DEFAULT_RESEARCH_COST_PER_LEVEL,
                DEFAULT_BATTLE_BOOST_BASE_COST,
                DEFAULT_BATTLE_BOOST_COST_PER_LEVEL,
                DEFAULT_BATTLE_BOOST_POWER_MULTIPLIER,
                DEFAULT_BATTLE_BOOST_SPEED_MULTIPLIER,
                DEFAULT_BATTLE_BOOST_RANGE_MULTIPLIER,
                DEFAULT_BATTLE_BOOST_STACK_LIMIT,
                DEFAULT_BATTLE_REPAIR_FUNDS_PER_HEALTH,
                DEFAULT_BATTLE_REPAIR_HEALTH_PER_PURCHASE,
                defaultSpecialistProfiles());
    }

    /** Keeps direct construction source-compatible with the pre-economy tower settings. */
    public TowerSettings(
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
        this(
                baseLimit,
                limitIncrement,
                hardCap,
                DEFAULT_TOWER_MAXIMUM_HIT_POINTS,
                arrowDamage,
                arrowRange,
                arrowAttackIntervalTicks,
                cannonDamage,
                cannonRange,
                cannonAttackIntervalTicks,
                cannonSplashRadius,
                DEFAULT_INDIVIDUAL_UPGRADE_BASE_SHARD_COST,
                DEFAULT_INDIVIDUAL_UPGRADE_BASE_CORE_COST,
                DEFAULT_INDIVIDUAL_UPGRADE_SHARD_COST_PER_LEVEL,
                DEFAULT_INDIVIDUAL_UPGRADE_CORE_COST_PER_LEVEL,
                DEFAULT_RESEARCH_BASE_COST,
                DEFAULT_RESEARCH_COST_PER_LEVEL,
                DEFAULT_BATTLE_BOOST_BASE_COST,
                DEFAULT_BATTLE_BOOST_COST_PER_LEVEL,
                DEFAULT_BATTLE_BOOST_POWER_MULTIPLIER,
                DEFAULT_BATTLE_BOOST_SPEED_MULTIPLIER,
                DEFAULT_BATTLE_BOOST_RANGE_MULTIPLIER,
                DEFAULT_BATTLE_BOOST_STACK_LIMIT,
                DEFAULT_BATTLE_REPAIR_FUNDS_PER_HEALTH,
                DEFAULT_BATTLE_REPAIR_HEALTH_PER_PURCHASE,
                defaultSpecialistProfiles());
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
        return profile(type).range();
    }

    public int damageFor(TowerType type) {
        return profile(type).damage();
    }

    public int attackIntervalTicksFor(TowerType type) {
        return profile(type).attackIntervalTicks();
    }

    public TowerProfile profile(TowerType type) {
        Objects.requireNonNull(type, "type");
        return switch (type) {
            case ARROW -> new TowerProfile(
                    arrowDamage,
                    arrowRange,
                    arrowAttackIntervalTicks,
                    0.0d,
                    0.0d,
                    0,
                    0,
                    0.0d,
                    0.0d,
                    1.0d,
                    1.0d,
                    1.0d,
                    0,
                    0);
            case CANNON -> new TowerProfile(
                    cannonDamage,
                    cannonRange,
                    cannonAttackIntervalTicks,
                    cannonSplashRadius,
                    0.0d,
                    0,
                    0,
                    0.0d,
                    0.0d,
                    1.0d,
                    1.0d,
                    1.0d,
                    0,
                    0);
            default -> specialistProfiles.get(type);
        };
    }

    public double areaRadiusFor(TowerType type) {
        return profile(type).areaRadius();
    }

    public double slowPercentFor(TowerType type) {
        return profile(type).slowPercent();
    }

    public int slowDurationTicksFor(TowerType type) {
        return profile(type).slowDurationTicks();
    }

    public int chainCountFor(TowerType type) {
        return profile(type).chainCount();
    }

    public double chainRadiusFor(TowerType type) {
        return profile(type).chainRadius();
    }

    public double supportRadius() {
        return profile(TowerType.SUPPORT).supportRadius();
    }

    public double supportDamageMultiplier() {
        return profile(TowerType.SUPPORT).supportDamageMultiplier();
    }

    public double supportSpeedMultiplier() {
        return profile(TowerType.SUPPORT).supportSpeedMultiplier();
    }

    public double supportRangeMultiplier() {
        return profile(TowerType.SUPPORT).supportRangeMultiplier();
    }

    public int supportStackLimit() {
        return profile(TowerType.SUPPORT).supportStackLimit();
    }

    public int burnDurationTicksFor(TowerType type) {
        return profile(type).burnDurationTicks();
    }

    private static Map<TowerType, TowerProfile> defaultSpecialistProfiles() {
        return Map.of(
                TowerType.FROST, TowerProfile.frostDefaults(),
                TowerType.LIGHTNING, TowerProfile.lightningDefaults(),
                TowerType.SUPPORT, TowerProfile.supportDefaults(),
                TowerType.SNIPER, TowerProfile.sniperDefaults(),
                TowerType.FLAME, TowerProfile.flameDefaults());
    }

    public int individualUpgradeShardCost(int currentLevel) {
        requireLevel(currentLevel, "currentLevel");
        return boundedCost(
                individualUpgradeBaseShardCost,
                individualUpgradeShardCostPerLevel,
                currentLevel - 1,
                "individual shard cost");
    }

    public int individualUpgradeCoreCost(int currentLevel) {
        requireLevel(currentLevel, "currentLevel");
        return boundedCost(
                individualUpgradeBaseCoreCost,
                individualUpgradeCoreCostPerLevel,
                currentLevel - 1,
                "individual core cost");
    }

    public int researchCost(int currentResearchLevel) {
        requireLevel(currentResearchLevel, "currentResearchLevel");
        return boundedCost(
                researchBaseCost,
                researchCostPerLevel,
                currentResearchLevel,
                "research cost");
    }

    public int battleBoostCost(int currentBoostLevel) {
        if (currentBoostLevel < 0) {
            throw new IllegalArgumentException("currentBoostLevel must not be negative");
        }
        return boundedCost(
                battleBoostBaseCost,
                battleBoostCostPerLevel,
                currentBoostLevel,
                "battle boost cost");
    }

    private static void requireLevel(int level, String name) {
        if (level <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static int boundedCost(int base, int increment, int levels, String name) {
        try {
            return Math.toIntExact(Math.addExact(
                    base,
                    Math.multiplyExact((long) increment, levels)));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(name + " is outside the integer range", overflow);
        }
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
