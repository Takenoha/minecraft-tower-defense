package io.github.takenoha.towerdefense.persistence;

/** Durable handoff state for legacy tower-upgrade materials. */
public enum TowerUpgradeReceiptState {
    RESERVED,
    SECURED,
    RETURN_PENDING,
    CLEAR_PENDING,
    CLEARED,
    RESTORED
}
