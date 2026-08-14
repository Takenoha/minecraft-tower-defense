package io.github.takenoha.towerdefense.persistence

/** Durable state of a core-repair stop-window operation. */
enum class CoreRepairOperationState {
    PREPARED,
    APPLIED,
    ROLLED_BACK,
}
