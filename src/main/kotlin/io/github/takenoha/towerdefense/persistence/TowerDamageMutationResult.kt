package io.github.takenoha.towerdefense.persistence

import java.util.Objects
import java.util.UUID
import kotlin.jvm.JvmRecord

/** Atomic result of one event-enemy attack against an installed tower. */
@JvmRecord
data class TowerDamageMutationResult(
    val outcome: OperationOutcome,
    val eventId: UUID,
    val teamId: UUID,
    val towerId: UUID,
    val attackerLogicalEnemyId: UUID,
    val damage: Long,
    val remainingHitPoints: Long,
    val destroyed: Boolean,
) {
    init {
        Objects.requireNonNull(outcome, "outcome")
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(teamId, "teamId")
        Objects.requireNonNull(towerId, "towerId")
        Objects.requireNonNull(attackerLogicalEnemyId, "attackerLogicalEnemyId")
        if (damage <= 0L || remainingHitPoints < 0L ||
            (destroyed && remainingHitPoints != 0L) ||
            (!destroyed && remainingHitPoints == 0L)
        ) {
            throw IllegalArgumentException("tower damage result is invalid")
        }
    }
}
