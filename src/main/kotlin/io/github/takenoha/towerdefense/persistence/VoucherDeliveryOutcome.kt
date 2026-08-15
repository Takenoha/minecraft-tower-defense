package io.github.takenoha.towerdefense.persistence

/** Result of preparing a voucher's recipient-fixed inventory delivery. */
enum class VoucherDeliveryOutcome {
    PREPARED,
    ALREADY_PREPARED,
    ALREADY_AVAILABLE,
    VOIDED,
}
