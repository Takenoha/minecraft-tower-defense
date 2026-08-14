package io.github.takenoha.towerdefense.persistence

/** Delivery state of a durable reward queue row. */
enum class RewardQueueStatus {
    PENDING,
    DELIVERED,
    VOIDED,
}
