package io.github.takenoha.towerdefense.persistence

/** Lifecycle of an event-scoped battle-funds account. */
enum class BattleFundsState {
    ACTIVE,
    SETTLED,
}
