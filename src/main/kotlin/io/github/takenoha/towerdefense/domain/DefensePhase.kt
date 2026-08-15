package io.github.takenoha.towerdefense.domain

import java.util.Objects

/** The durable lifecycle phase of a defense session. */
enum class DefensePhase {
    COUNTDOWN,
    PREPARATION,
    WAVE_ACTIVE,
    INTERMISSION,
    VICTORY,
    DEFEAT,
    ABORTED,
    RECOVERY,
    ;

    fun isTerminal(): Boolean = when (this) {
        VICTORY, DEFEAT, ABORTED, RECOVERY -> true
        COUNTDOWN, PREPARATION, WAVE_ACTIVE, INTERMISSION -> false
    }

    fun canTransitionTo(next: DefensePhase): Boolean {
        Objects.requireNonNull(next, "next")
        if (this == next) return isTerminal()
        return when (this) {
            COUNTDOWN -> next == PREPARATION || next == DEFEAT || next == ABORTED || next == RECOVERY
            PREPARATION -> next == WAVE_ACTIVE || next == DEFEAT || next == ABORTED || next == RECOVERY
            WAVE_ACTIVE -> next == INTERMISSION || next == VICTORY || next == DEFEAT || next == ABORTED || next == RECOVERY
            INTERMISSION -> next == WAVE_ACTIVE || next == DEFEAT || next == ABORTED || next == RECOVERY
            VICTORY, DEFEAT, ABORTED, RECOVERY -> false
        }
    }
}
