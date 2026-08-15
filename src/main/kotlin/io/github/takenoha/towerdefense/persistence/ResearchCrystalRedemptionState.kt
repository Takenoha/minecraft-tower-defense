package io.github.takenoha.towerdefense.persistence

/** Physical-to-database handoff state for one crystal redemption operation. */
enum class ResearchCrystalRedemptionState {
    PREPARED,
    APPLIED,
    ROLLED_BACK,
}
