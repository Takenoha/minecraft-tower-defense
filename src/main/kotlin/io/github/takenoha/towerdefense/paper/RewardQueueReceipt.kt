package io.github.takenoha.towerdefense.paper

import java.util.Objects
import java.util.UUID
import kotlin.jvm.JvmRecord

/** Durable identity copied onto an inventory stack while its queue delivery is being committed. */
@JvmRecord
data class RewardQueueReceipt(
    val queueId: UUID,
    val operationId: UUID,
) {
    init {
        Objects.requireNonNull(queueId, "queueId")
        Objects.requireNonNull(operationId, "operationId")
    }
}
