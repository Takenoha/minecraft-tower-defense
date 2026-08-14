package io.github.takenoha.towerdefense.persistence

/** Durable handoff state for legacy tower-upgrade materials. */
enum class TowerUpgradeReceiptState {
    RESERVED,
    SECURED,
    RETURN_PENDING,
    CLEAR_PENDING,
    CLEARED,
    RESTORED,
}
