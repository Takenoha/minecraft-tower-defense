package io.github.takenoha.towerdefense.persistence

import java.time.Instant
import java.util.Objects
import java.util.Optional
import java.util.UUID
import kotlin.jvm.JvmRecord

/** Durable block ledger row, including its idempotence and recovery markers. */
@JvmRecord
data class StoredBlockChange(
    val change: BlockChange,
    val status: BlockChangeStatus,
    val prepareOperationId: UUID,
    val applyOperationId: Optional<UUID>,
    val rollbackOperationId: Optional<UUID>,
    val preparedAt: Instant,
    val appliedAt: Optional<Instant>,
    val resolvedAt: Optional<Instant>,
) {
    init {
        Objects.requireNonNull(change, "change")
        Objects.requireNonNull(status, "status")
        Objects.requireNonNull(prepareOperationId, "prepareOperationId")
        Objects.requireNonNull(applyOperationId, "applyOperationId")
        Objects.requireNonNull(rollbackOperationId, "rollbackOperationId")
        Objects.requireNonNull(preparedAt, "preparedAt")
        Objects.requireNonNull(appliedAt, "appliedAt")
        Objects.requireNonNull(resolvedAt, "resolvedAt")
        if ((status == BlockChangeStatus.PREPARED) != appliedAt.isEmpty) {
            throw IllegalArgumentException(
                "only an applied or resolved block change may have appliedAt",
            )
        }
        if ((status == BlockChangeStatus.SETTLED ||
                status == BlockChangeStatus.ROLLED_BACK ||
                status == BlockChangeStatus.CONFLICT) != resolvedAt.isPresent
        ) {
            throw IllegalArgumentException(
                "resolvedAt must match the terminal block ledger status",
            )
        }
    }
}
