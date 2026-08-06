package io.github.takenoha.towerdefense.domain;

import io.github.takenoha.towerdefense.config.CoreSettings;
import java.math.BigInteger;
import java.util.Objects;

/**
 * Immutable quote for restoring a damaged core to full health.
 *
 * <p>The quote is deliberately calculated outside Bukkit so the same arithmetic can be used by
 * the GUI, persistence tests, and a future admin audit command. One repair unit covers a fixed
 * amount of missing HP; both material and shard costs scale with the team's highest clear level.
 */
public record CoreRepairCost(
        long repairAmount,
        long repairUnits,
        long vanillaMaterialAmount,
        long defenseShardAmount,
        long highestClearedLevel) {
    public CoreRepairCost {
        if (repairAmount <= 0L) {
            throw new IllegalArgumentException("repairAmount must be positive");
        }
        if (repairUnits <= 0L) {
            throw new IllegalArgumentException("repairUnits must be positive");
        }
        if (vanillaMaterialAmount <= 0L) {
            throw new IllegalArgumentException("vanillaMaterialAmount must be positive");
        }
        if (defenseShardAmount <= 0L) {
            throw new IllegalArgumentException("defenseShardAmount must be positive");
        }
        if (highestClearedLevel < 0L) {
            throw new IllegalArgumentException("highestClearedLevel must be non-negative");
        }
    }

    public static CoreRepairCost forMissing(
            long missingHitPoints,
            long highestClearedLevel,
            CoreSettings settings) {
        Objects.requireNonNull(settings, "settings");
        if (missingHitPoints <= 0L) {
            throw new IllegalArgumentException("missingHitPoints must be positive");
        }
        if (highestClearedLevel < 0L) {
            throw new IllegalArgumentException("highestClearedLevel must be non-negative");
        }
        long repairUnits = ceilDiv(missingHitPoints, settings.repairHealthPerUnit());
        BigInteger levelCost = BigInteger.valueOf(highestClearedLevel)
                .multiply(BigInteger.valueOf(settings.repairCostPerClearLevel()));
        BigInteger materialPerUnit = levelCost.add(
                BigInteger.valueOf(settings.repairMaterialBaseCost()));
        BigInteger shardsPerUnit = levelCost.add(
                BigInteger.valueOf(settings.repairShardBaseCost()));
        return new CoreRepairCost(
                missingHitPoints,
                repairUnits,
                multiplyToLong(materialPerUnit, repairUnits, "vanillaMaterialAmount"),
                multiplyToLong(shardsPerUnit, repairUnits, "defenseShardAmount"),
                highestClearedLevel);
    }

    private static long ceilDiv(long dividend, long divisor) {
        if (divisor <= 0L) {
            throw new IllegalArgumentException("repair health unit must be positive");
        }
        long quotient = dividend / divisor;
        return dividend % divisor == 0L ? quotient : Math.addExact(quotient, 1L);
    }

    private static long multiplyToLong(BigInteger left, long right, String name) {
        try {
            return left.multiply(BigInteger.valueOf(right)).longValueExact();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(name + " exceeds Long.MAX_VALUE", overflow);
        }
    }
}
