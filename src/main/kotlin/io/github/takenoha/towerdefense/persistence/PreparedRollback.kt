package io.github.takenoha.towerdefense.persistence

import java.util.UUID
import kotlin.jvm.JvmRecord

/** A rollback decision whose physical/database completion was interrupted. */
@JvmRecord
data class PreparedRollback(
    val operationId: UUID,
    val decision: BlockRollbackDecision,
)
