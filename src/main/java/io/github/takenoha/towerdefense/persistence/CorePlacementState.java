package io.github.takenoha.towerdefense.persistence;

/** Durable state of the physical core placement stop window. */
public enum CorePlacementState {
    PREPARED,
    APPLIED,
    ROLLED_BACK
}
