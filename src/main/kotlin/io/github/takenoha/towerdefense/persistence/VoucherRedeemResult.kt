package io.github.takenoha.towerdefense.persistence

import java.util.Objects
import kotlin.jvm.JvmRecord

/** Voucher plus its durable redeem operation after a reservation read. */
@JvmRecord
data class VoucherRedeemResult(
    val outcome: OperationOutcome,
    val voucher: ResourceVoucher,
    val operation: VoucherRedeemOperation,
) {
    init {
        Objects.requireNonNull(outcome, "outcome")
        Objects.requireNonNull(voucher, "voucher")
        Objects.requireNonNull(operation, "operation")
    }
}
