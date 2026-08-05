package io.github.takenoha.towerdefense.persistence;

/** Result of preparing a voucher's recipient-fixed inventory delivery. */
public enum VoucherDeliveryOutcome {
    PREPARED,
    ALREADY_PREPARED,
    ALREADY_AVAILABLE,
    VOIDED
}
