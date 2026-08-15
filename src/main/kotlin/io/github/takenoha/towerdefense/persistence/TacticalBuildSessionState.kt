package io.github.takenoha.towerdefense.persistence

/** Durable state of a tactical build selection session. */
enum class TacticalBuildSessionState {
    GENERATED,
    SELECTED,
    ACTIVE,
    TERMINAL,
    CANCELLED,
    RECOVERY_HOLD,
}
