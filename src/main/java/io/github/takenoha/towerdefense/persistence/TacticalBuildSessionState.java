package io.github.takenoha.towerdefense.persistence;

/** Durable state of a tactical build selection session. */
public enum TacticalBuildSessionState {
    GENERATED,
    SELECTED,
    ACTIVE,
    TERMINAL,
    CANCELLED,
    RECOVERY_HOLD
}
