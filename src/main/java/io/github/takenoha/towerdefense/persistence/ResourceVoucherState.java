package io.github.takenoha.towerdefense.persistence;

/** Durable lifecycle of a team-bound point voucher. */
public enum ResourceVoucherState {
    PENDING_DELIVERY,
    AVAILABLE,
    RESERVED,
    REDEEMED,
    VOIDED
}
