package io.github.takenoha.towerdefense.persistence

/** Durable state of the inventory handoff receipt for a voucher. */
enum class VoucherDeliveryState {
    PREPARED,
    APPLIED,
    ROLLED_BACK,
}
