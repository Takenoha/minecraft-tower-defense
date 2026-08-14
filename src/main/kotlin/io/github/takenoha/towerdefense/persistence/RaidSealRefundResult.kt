package io.github.takenoha.towerdefense.persistence

import kotlin.jvm.JvmRecord

/** Result of the one-time technical refund of a consumed raid seal. */
@JvmRecord
data class RaidSealRefundResult(
    val outcome: OperationOutcome,
    val returnedSeal: RaidSeal,
)
