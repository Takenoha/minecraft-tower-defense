package io.github.takenoha.towerdefense.persistence

import java.util.Objects
import kotlin.jvm.JvmRecord

/** Voucher plus its durable delivery operation after a prepare/reconcile read. */
@JvmRecord
data class VoucherDeliveryResult(
    val outcome: VoucherDeliveryOutcome,
    val voucher: ResourceVoucher,
    val operation: VoucherDeliveryOperation?,
) {
    init {
        Objects.requireNonNull(outcome, "outcome")
        Objects.requireNonNull(voucher, "voucher")
    }
}
