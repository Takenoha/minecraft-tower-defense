package io.github.takenoha.towerdefense.persistence

/** Result of reserving one durable reward queue row for a Paper inventory handoff. */
enum class RewardDeliveryOutcome {
    ACQUIRED,
    ALREADY_ACQUIRED,
    ALREADY_DELIVERED,
    HELD_BY_OTHER,
    VOIDED,
}
