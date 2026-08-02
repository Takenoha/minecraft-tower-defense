package io.github.takenoha.towerdefense.domain;

/** Immutable state of a team's core within a defense session. */
public record CoreState(long maximumHitPoints, long currentHitPoints, boolean present) {
    public CoreState {
        if (maximumHitPoints <= 0L) {
            throw new IllegalArgumentException("maximumHitPoints must be positive");
        }
        if (currentHitPoints < 0L || currentHitPoints > maximumHitPoints) {
            throw new IllegalArgumentException(
                    "currentHitPoints must be between zero and maximumHitPoints");
        }
        if (present != (currentHitPoints > 0L)) {
            throw new IllegalArgumentException(
                    "a present core must have HP and a zero-HP core must be absent");
        }
    }

    /** Creates a present core at full health. */
    public static CoreState intact(long maximumHitPoints) {
        return new CoreState(maximumHitPoints, maximumHitPoints, true);
    }

    /** Creates the persisted state of a core that has reached zero HP and disappeared. */
    public static CoreState destroyed(long maximumHitPoints) {
        return new CoreState(maximumHitPoints, 0L, false);
    }

    public boolean isDestroyed() {
        return !present;
    }

    /** Applies non-negative damage, saturating at zero without arithmetic overflow. */
    public CoreState damage(long amount) {
        requireNonNegative("amount", amount);
        if (amount == 0L || isDestroyed()) {
            return this;
        }
        long remaining = amount >= currentHitPoints ? 0L : currentHitPoints - amount;
        return new CoreState(maximumHitPoints, remaining, remaining > 0L);
    }

    /** Repairs a present core, saturating at maximum HP. Destroyed cores cannot be repaired. */
    public CoreState repair(long amount) {
        requireNonNegative("amount", amount);
        if (isDestroyed()) {
            throw new IllegalStateException("a destroyed core must be replaced, not repaired");
        }
        if (amount == 0L || currentHitPoints == maximumHitPoints) {
            return this;
        }
        long missing = maximumHitPoints - currentHitPoints;
        long restored = Math.min(amount, missing);
        return new CoreState(maximumHitPoints, currentHitPoints + restored, true);
    }

    private static void requireNonNegative(String name, long value) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
