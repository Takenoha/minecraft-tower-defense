package io.github.takenoha.towerdefense.persistence;

/** Durable states for the tower physical placement stop window. */
public enum TowerPlacementState {
    PREPARED,
    APPLIED,
    ROLLED_BACK
}
