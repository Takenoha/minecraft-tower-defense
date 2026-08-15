package io.github.takenoha.towerdefense.persistence

import java.time.Instant
import java.util.Objects
import java.util.UUID
import kotlin.jvm.JvmRecord

/** Immutable team-shared currency account scoped to one defense event. */
@JvmRecord
data class BattleFunds(
    val eventId: UUID,
    val teamId: UUID,
    val balance: Long,
    val totalEarned: Long,
    val totalSpent: Long,
    val state: BattleFundsState,
    val updatedAt: Instant,
) {
    init {
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(teamId, "teamId")
        Objects.requireNonNull(state, "state")
        Objects.requireNonNull(updatedAt, "updatedAt")
        if (balance < 0L || totalEarned < 0L || totalSpent < 0L) {
            throw IllegalArgumentException("battle funds totals must not be negative")
        }
        if (totalSpent > totalEarned) {
            throw IllegalArgumentException("totalSpent cannot exceed totalEarned")
        }
        if (state == BattleFundsState.SETTLED && balance != 0L) {
            throw IllegalArgumentException("settled battle funds must have zero balance")
        }
    }
}
