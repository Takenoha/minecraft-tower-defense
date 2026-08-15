package io.github.takenoha.towerdefense.persistence

import java.util.Objects
import java.util.UUID
import kotlin.jvm.JvmRecord

/** Values used by the main-thread pickup feedback after a committed claim. */
@JvmRecord
data class ResourcePickupFeedback(
    val eventId: UUID,
    val playerId: UUID,
    val resourceType: ResourceType,
    val claimedQuantity: Int,
    val eventPlayerTotal: Long,
    val teamBalance: Long,
) {
    init {
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(playerId, "playerId")
        Objects.requireNonNull(resourceType, "resourceType")
        if (claimedQuantity <= 0 || eventPlayerTotal < 0L || teamBalance < 0L) {
            throw IllegalArgumentException("pickup feedback quantities are invalid")
        }
    }
}
