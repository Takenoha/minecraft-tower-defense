package io.github.takenoha.towerdefense.persistence

/** Durable resolution state of the vanilla material receipt. */
enum class CoreRepairReceiptState {
    RESERVED,
    SECURED,
    RETURN_PENDING,
    CLEAR_PENDING,
    CLEARED,
    RESTORED,
}
