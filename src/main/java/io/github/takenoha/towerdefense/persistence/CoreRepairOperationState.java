package io.github.takenoha.towerdefense.persistence;

/** Durable state of a core-repair stop-window operation. */
public enum CoreRepairOperationState {
    PREPARED,
    APPLIED,
    ROLLED_BACK
}
