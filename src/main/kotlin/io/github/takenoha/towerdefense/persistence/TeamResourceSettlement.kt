package io.github.takenoha.towerdefense.persistence

import io.github.takenoha.towerdefense.domain.DefensePhase
import java.util.Objects
import java.util.UUID
import kotlin.jvm.JvmRecord

/** Immutable terminal totals used for the player-facing settlement message. */
@JvmRecord
data class TeamResourceSettlement(
    val eventId: UUID,
    val teamId: UUID,
    val phase: DefensePhase,
    val defensePoints: Long,
    val enhancementPoints: Long,
) {
    init {
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(teamId, "teamId")
        Objects.requireNonNull(phase, "phase")
        if (defensePoints < 0L || enhancementPoints < 0L) {
            throw IllegalArgumentException("settlement quantities must not be negative")
        }
    }
}
