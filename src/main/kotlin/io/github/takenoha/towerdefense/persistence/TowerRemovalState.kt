package io.github.takenoha.towerdefense.persistence

/** Durable states for the tower removal and item-return stop window. */
enum class TowerRemovalState {
    PREPARED,
    APPLIED,
    ROLLED_BACK,
}
