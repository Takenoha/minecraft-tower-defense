package io.github.takenoha.towerdefense.paper

import java.util.Objects
import java.util.UUID
import kotlin.jvm.JvmRecord

/** PDC identity attached to a physical display of a database-owned escrow drop. */
@JvmRecord
data class TaggedEscrowDrop(
    val eventId: UUID,
    val dropId: UUID,
) {
    init {
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(dropId, "dropId")
    }
}
