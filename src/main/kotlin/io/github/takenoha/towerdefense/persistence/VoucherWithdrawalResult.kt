package io.github.takenoha.towerdefense.persistence

import kotlin.jvm.JvmRecord

/** Result of a wallet debit plus one durable voucher creation. */
@JvmRecord
data class VoucherWithdrawalResult(
    val outcome: OperationOutcome,
    val voucher: ResourceVoucher,
)
