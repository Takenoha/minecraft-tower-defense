package io.github.takenoha.towerdefense.domain;

import java.util.Objects;

/** The durable lifecycle phase of a defense session. */
public enum DefensePhase {
    COUNTDOWN,
    PREPARATION,
    WAVE_ACTIVE,
    INTERMISSION,
    VICTORY,
    DEFEAT,
    ABORTED,
    RECOVERY;

    /**
     * Returns whether this phase is an end state. Recovery is an end state for the
     * gameplay session; rollback progress is persisted separately by the runtime.
     */
    public boolean isTerminal() {
        return switch (this) {
            case VICTORY, DEFEAT, ABORTED, RECOVERY -> true;
            case COUNTDOWN, PREPARATION, WAVE_ACTIVE, INTERMISSION -> false;
        };
    }

    /** Returns whether a direct lifecycle transition is permitted. */
    public boolean canTransitionTo(DefensePhase next) {
        Objects.requireNonNull(next, "next");
        if (this == next) {
            return isTerminal();
        }
        return switch (this) {
            case COUNTDOWN -> next == PREPARATION
                    || next == DEFEAT
                    || next == ABORTED
                    || next == RECOVERY;
            case PREPARATION -> next == WAVE_ACTIVE
                    || next == DEFEAT
                    || next == ABORTED
                    || next == RECOVERY;
            case WAVE_ACTIVE -> next == INTERMISSION
                    || next == VICTORY
                    || next == DEFEAT
                    || next == ABORTED
                    || next == RECOVERY;
            case INTERMISSION -> next == WAVE_ACTIVE
                    || next == DEFEAT
                    || next == ABORTED
                    || next == RECOVERY;
            case VICTORY, DEFEAT, ABORTED, RECOVERY -> false;
        };
    }
}
