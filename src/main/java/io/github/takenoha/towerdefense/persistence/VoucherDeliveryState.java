package io.github.takenoha.towerdefense.persistence;

/** Durable state of the inventory handoff receipt for a voucher. */
public enum VoucherDeliveryState {
    PREPARED,
    APPLIED,
    ROLLED_BACK
}
