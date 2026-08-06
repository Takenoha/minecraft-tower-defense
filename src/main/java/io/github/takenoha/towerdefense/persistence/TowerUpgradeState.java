package io.github.takenoha.towerdefense.persistence;

/** Two-phase physical-material handoff state for one individual tower upgrade. */
public enum TowerUpgradeState {
    PREPARED,
    APPLIED,
    ROLLED_BACK
}
