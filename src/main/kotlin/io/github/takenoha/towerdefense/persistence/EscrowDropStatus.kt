package io.github.takenoha.towerdefense.persistence

/** Durable lifecycle of a virtual event drop. */
enum class EscrowDropStatus {
    HELD,
    SETTLED,
    VOIDED,
}
