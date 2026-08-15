package io.github.takenoha.towerdefense.persistence

/** Durable state of the receipt-protected voucher deposit operation. */
enum class VoucherRedeemState {
    PREPARED,
    APPLIED,
    ROLLED_BACK,
}
