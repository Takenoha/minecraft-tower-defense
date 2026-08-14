package io.github.takenoha.towerdefense.persistence

/** Durable state of one write-ahead block mutation. */
enum class BlockChangeStatus {
    PREPARED,
    APPLIED,
    SETTLED,
    ROLLED_BACK,
    CONFLICT,
}
