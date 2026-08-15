package io.github.takenoha.towerdefense.persistence

import java.util.Objects
import java.util.Optional
import kotlin.jvm.JvmRecord

/** Result of an idempotent escrow claim attempt. */
@JvmRecord
data class EscrowClaimResult(
    val outcome: OperationOutcome,
    val claimedQuantity: Int,
    val pickupFeedback: Optional<ResourcePickupFeedback>,
) {
    constructor(outcome: OperationOutcome, claimedQuantity: Int) : this(
        outcome,
        claimedQuantity,
        Optional.empty(),
    )

    init {
        Objects.requireNonNull(outcome, "outcome")
        Objects.requireNonNull(pickupFeedback, "pickupFeedback")
        if (claimedQuantity < 0) {
            throw IllegalArgumentException("claimedQuantity must not be negative")
        }
    }
}
