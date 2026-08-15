package io.github.takenoha.towerdefense.persistence

/** Durable states for the tower physical placement stop window. */
enum class TowerPlacementState {
    PREPARED,
    APPLIED,
    ROLLED_BACK,
}
