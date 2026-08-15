package io.github.takenoha.towerdefense.persistence

import java.time.Instant
import java.util.Objects
import java.util.UUID
import kotlin.jvm.JvmRecord

/** Cumulative temporary boost for one tower during one defense event. */
@JvmRecord
data class BattleBoost(
    val eventId: UUID,
    val teamId: UUID,
    val towerId: UUID,
    val kind: BattleBoostKind,
    val level: Int,
    val multiplier: Double,
    val updatedAt: Instant,
) {
    init {
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(teamId, "teamId")
        Objects.requireNonNull(towerId, "towerId")
        Objects.requireNonNull(kind, "kind")
        Objects.requireNonNull(updatedAt, "updatedAt")
        if (level <= 0 || !multiplier.isFinite() || multiplier <= 0.0) {
            throw IllegalArgumentException("battle boost values are invalid")
        }
    }
}
