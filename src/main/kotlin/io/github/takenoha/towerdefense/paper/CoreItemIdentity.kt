package io.github.takenoha.towerdefense.paper

import java.util.Objects
import java.util.Optional
import java.util.UUID
import kotlin.jvm.JvmRecord

/** The durable identity carried by a crafted or itemized core item. */
@JvmRecord
data class CoreItemIdentity(
    val itemId: UUID,
    val teamId: Optional<UUID>,
    val coreId: Optional<UUID>,
) {
    constructor(itemId: UUID, teamId: Optional<UUID>) : this(itemId, teamId, Optional.empty())

    init {
        Objects.requireNonNull(itemId, "itemId")
        Objects.requireNonNull(teamId, "teamId")
        Objects.requireNonNull(coreId, "coreId")
    }

    fun isBound(): Boolean = teamId.isPresent
}
