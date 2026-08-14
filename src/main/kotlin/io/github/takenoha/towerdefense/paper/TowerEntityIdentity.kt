package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.domain.TowerType
import java.util.Objects
import java.util.UUID
import kotlin.jvm.JvmRecord

/** PDC identity carried by the persistent Paper entity representing a tower. */
@JvmRecord
data class TowerEntityIdentity(
    val towerId: UUID,
    val teamId: UUID,
    val type: TowerType,
    val individualLevel: Int,
) {
    init {
        Objects.requireNonNull(towerId, "towerId")
        Objects.requireNonNull(teamId, "teamId")
        Objects.requireNonNull(type, "type")
        require(individualLevel > 0) { "individualLevel must be positive" }
    }
}
