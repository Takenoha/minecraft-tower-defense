package io.github.takenoha.towerdefense.domain

import java.time.Instant
import java.util.Objects
import java.util.UUID
import kotlin.jvm.JvmRecord

/** Durable research cap for one tower type owned by a team. */
@JvmRecord
data class TowerResearch(
    val teamId: UUID,
    val towerType: TowerType,
    val researchLevel: Int,
    val updatedAt: Instant,
) {
    init {
        Objects.requireNonNull(teamId, "teamId")
        Objects.requireNonNull(towerType, "towerType")
        if (researchLevel <= 0) {
            throw IllegalArgumentException("researchLevel must be positive")
        }
        Objects.requireNonNull(updatedAt, "updatedAt")
    }

    companion object {
        @JvmStatic
        fun initial(teamId: UUID, towerType: TowerType, createdAt: Instant): TowerResearch =
            TowerResearch(teamId, towerType, 1, createdAt)
    }
}
