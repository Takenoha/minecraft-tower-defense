package io.github.takenoha.towerdefense.persistence

import java.time.Instant
import java.util.Objects
import java.util.Optional
import java.util.UUID
import kotlin.jvm.JvmRecord

/** One idempotently issued, still database-owned reward. */
@JvmRecord
data class RewardQueueEntry(
    val queueId: UUID,
    val eventId: UUID,
    val scope: RewardQueueScope,
    val recipientId: UUID,
    val itemId: String,
    val itemPayload: String,
    val quantity: Int,
    val sourceDropId: UUID,
    val status: RewardQueueStatus,
    val issuedOperationId: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
    val teamClaimDeadline: Optional<Instant>,
) {
    /** Keeps direct construction source-compatible with schema v9 callers. */
    constructor(
        queueId: UUID,
        eventId: UUID,
        scope: RewardQueueScope,
        recipientId: UUID,
        itemId: String,
        itemPayload: String,
        quantity: Int,
        sourceDropId: UUID,
        status: RewardQueueStatus,
        issuedOperationId: UUID,
        createdAt: Instant,
        updatedAt: Instant,
    ) : this(
        queueId,
        eventId,
        scope,
        recipientId,
        itemId,
        itemPayload,
        quantity,
        sourceDropId,
        status,
        issuedOperationId,
        createdAt,
        updatedAt,
        Optional.empty(),
    )

    init {
        Objects.requireNonNull(queueId, "queueId")
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(scope, "scope")
        Objects.requireNonNull(recipientId, "recipientId")
        Objects.requireNonNull(sourceDropId, "sourceDropId")
        Objects.requireNonNull(status, "status")
        Objects.requireNonNull(issuedOperationId, "issuedOperationId")
        Objects.requireNonNull(createdAt, "createdAt")
        Objects.requireNonNull(updatedAt, "updatedAt")
        Objects.requireNonNull(teamClaimDeadline, "teamClaimDeadline")
        if (scope == RewardQueueScope.PLAYER && teamClaimDeadline.isPresent) {
            throw IllegalArgumentException("PLAYER queue rows cannot have a team deadline")
        }
        if (itemId.isBlank()) {
            throw IllegalArgumentException("itemId must not be blank")
        }
        if (itemPayload.isBlank()) {
            throw IllegalArgumentException("itemPayload must not be blank")
        }
        if (quantity <= 0) {
            throw IllegalArgumentException("quantity must be positive")
        }
    }
}
