package io.github.takenoha.towerdefense.persistence

import java.util.Objects
import java.util.UUID
import kotlin.jvm.JvmRecord

/** Durable current/max HP snapshot returned by a tower repair mutation. */
@JvmRecord
data class TowerDurability(
    val towerId: UUID,
    val teamId: UUID,
    val currentHitPoints: Long,
    val maximumHitPoints: Long,
) {
    init {
        Objects.requireNonNull(towerId, "towerId")
        Objects.requireNonNull(teamId, "teamId")
        if (maximumHitPoints <= 0L || currentHitPoints < 0L || currentHitPoints > maximumHitPoints) {
            throw IllegalArgumentException("tower durability is invalid")
        }
    }
}
