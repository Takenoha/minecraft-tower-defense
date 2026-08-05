package io.github.takenoha.towerdefense.persistence;

/** Durable resolution state of the vanilla material receipt. */
public enum CoreRepairReceiptState {
    RESERVED,
    SECURED,
    RETURN_PENDING,
    CLEAR_PENDING,
    CLEARED,
    RESTORED
}
