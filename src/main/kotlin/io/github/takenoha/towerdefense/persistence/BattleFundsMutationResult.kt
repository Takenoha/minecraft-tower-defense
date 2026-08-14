package io.github.takenoha.towerdefense.persistence

import kotlin.jvm.JvmRecord

/** Result of one idempotent event-funds credit or spend operation. */
@JvmRecord
data class BattleFundsMutationResult(
    val outcome: OperationOutcome,
    val funds: BattleFunds,
)
