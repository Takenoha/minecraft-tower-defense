package io.github.takenoha.towerdefense.persistence

/** Durable state of the physical core placement stop window. */
enum class CorePlacementState {
    PREPARED,
    APPLIED,
    ROLLED_BACK,
}
