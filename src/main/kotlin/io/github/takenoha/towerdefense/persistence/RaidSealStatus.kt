package io.github.takenoha.towerdefense.persistence

/** Durable state of the single-use challenge token. */
enum class RaidSealStatus {
    AVAILABLE,
    RESERVED,
    CONSUMED,
    REFUNDED,
}
