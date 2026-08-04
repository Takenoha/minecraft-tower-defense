package io.github.takenoha.towerdefense.runtime;

/**
 * Main-thread cadence for one enemy that has reached the core.
 *
 * <p>The first attack is due immediately. Later attacks are scheduled from the tick at which
 * the previous attack was processed, so a delayed server tick does not cause a burst of catch-up
 * damage.</p>
 */
public final class CoreAttackSchedule {
    private final long intervalTicks;
    private long nextAttackTick = -1L;

    public CoreAttackSchedule(long intervalTicks) {
        if (intervalTicks <= 0L) {
            throw new IllegalArgumentException("intervalTicks must be > 0");
        }
        this.intervalTicks = intervalTicks;
    }

    /** Returns whether an attack is due, advancing the schedule when one is claimed. */
    public boolean tryClaim(long currentTick) {
        if (currentTick < 0L) {
            throw new IllegalArgumentException("currentTick must not be negative");
        }
        if (nextAttackTick >= 0L && currentTick < nextAttackTick) {
            return false;
        }
        nextAttackTick = currentTick > Long.MAX_VALUE - intervalTicks
                ? Long.MAX_VALUE
                : currentTick + intervalTicks;
        return true;
    }
}
