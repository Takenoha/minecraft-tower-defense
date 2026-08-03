package io.github.takenoha.towerdefense.persistence;

/** Durable lifecycle of a virtual event drop. */
public enum EscrowDropStatus {
    HELD,
    SETTLED,
    VOIDED
}
