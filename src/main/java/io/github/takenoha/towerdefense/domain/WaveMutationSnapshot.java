package io.github.takenoha.towerdefense.domain;

import java.util.Objects;

/**
 * Immutable start-time snapshot of a selected wave mutation and all coefficients it uses.
 * The snapshot is persisted with the defense session so a config reload cannot mix event rules.
 */
public record WaveMutationSnapshot(
        WaveMutation mutation,
        double enemySpeedMultiplier,
        double enemyHealthMultiplier,
        double enemyCountMultiplier,
        double rewardMultiplier) {

    public WaveMutationSnapshot {
        mutation = Objects.requireNonNull(mutation, "mutation");
        requirePositiveFinite("enemySpeedMultiplier", enemySpeedMultiplier);
        requirePositiveFinite("enemyHealthMultiplier", enemyHealthMultiplier);
        requirePositiveFinite("enemyCountMultiplier", enemyCountMultiplier);
        requirePositiveFinite("rewardMultiplier", rewardMultiplier);
        if (mutation == WaveMutation.NONE
                && (enemySpeedMultiplier != 1.0d
                        || enemyHealthMultiplier != 1.0d
                        || enemyCountMultiplier != 1.0d
                        || rewardMultiplier != 1.0d)) {
            throw new IllegalArgumentException(
                    "NONE wave mutation must use neutral coefficients");
        }
    }

    /** Returns the neutral snapshot used by legacy starts and migrated rows. */
    public static WaveMutationSnapshot none() {
        return new WaveMutationSnapshot(WaveMutation.NONE, 1.0d, 1.0d, 1.0d, 1.0d);
    }

    public boolean active() {
        return mutation != WaveMutation.NONE;
    }

    /** Scales an event wave count upward, rounding fractional enemies up. */
    public long scaleEnemyCount(long baseCount) {
        return scalePositive("baseCount", baseCount, enemyCountMultiplier);
    }

    /** Scales an integral reward upward, rounding fractional units up. */
    public int scaleReward(int baseQuantity) {
        if (baseQuantity < 0) {
            throw new IllegalArgumentException("baseQuantity must not be negative");
        }
        long scaled = scalePositive("baseQuantity", baseQuantity, rewardMultiplier);
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, scaled));
    }

    /** Scales an event-currency reward without narrowing it before overflow checks. */
    public long scaleReward(long baseQuantity) {
        if (baseQuantity < 0L) {
            throw new IllegalArgumentException("baseQuantity must not be negative");
        }
        return scalePositive("baseQuantity", baseQuantity, rewardMultiplier);
    }

    /** Scales a percentage reward and caps the result at 100 percent. */
    public int scalePercent(int basePercent) {
        if (basePercent < 0 || basePercent > 100) {
            throw new IllegalArgumentException("basePercent must be between 0 and 100");
        }
        return Math.toIntExact(Math.min(100L, scalePositive(
                "basePercent", basePercent, rewardMultiplier)));
    }

    private static long scalePositive(String name, long value, double multiplier) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        if (value == 0L || multiplier == 1.0d) {
            return value;
        }
        double scaled = value * multiplier;
        if (!Double.isFinite(scaled) || scaled > Long.MAX_VALUE) {
            throw new IllegalArgumentException(name + " scaling overflows long");
        }
        long result = (long) Math.ceil(scaled);
        return result > 0L ? result : 1L;
    }

    private static void requirePositiveFinite(String name, double value) {
        if (!Double.isFinite(value) || value <= 0.0d) {
            throw new IllegalArgumentException(name + " must be finite and > 0");
        }
    }
}
