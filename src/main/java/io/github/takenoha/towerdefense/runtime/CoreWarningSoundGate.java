package io.github.takenoha.towerdefense.runtime;

/** Main-thread debounce gate for the live core-damage warning sound. */
public final class CoreWarningSoundGate {
    private final long minimumIntervalTicks;
    private long lastPlayedTick = Long.MIN_VALUE;

    public CoreWarningSoundGate(long minimumIntervalTicks) {
        if (minimumIntervalTicks <= 0L) {
            throw new IllegalArgumentException("minimumIntervalTicks must be positive");
        }
        this.minimumIntervalTicks = minimumIntervalTicks;
    }

    public boolean tryClaim(long currentTick) {
        if (lastPlayedTick != Long.MIN_VALUE
                && currentTick - lastPlayedTick < minimumIntervalTicks) {
            return false;
        }
        lastPlayedTick = currentTick;
        return true;
    }

    public long minimumIntervalTicks() {
        return minimumIntervalTicks;
    }
}
