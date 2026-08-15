package io.github.takenoha.towerdefense.runtime

import io.github.takenoha.towerdefense.domain.EnemyRole
import java.util.Objects
import java.util.UUID
import kotlin.jvm.JvmRecord

/** Durable logical identity carried by a physical event enemy. */
@JvmRecord
data class TaggedEnemy(
    val eventId: UUID,
    val logicalEnemyId: UUID,
    val role: EnemyRole,
) {
    /** Keeps legacy callers safe by treating untyped enemies as normal enemies. */
    constructor(eventId: UUID, logicalEnemyId: UUID) : this(eventId, logicalEnemyId, EnemyRole.NORMAL)

    init {
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(logicalEnemyId, "logicalEnemyId")
        Objects.requireNonNull(role, "role")
    }
}
