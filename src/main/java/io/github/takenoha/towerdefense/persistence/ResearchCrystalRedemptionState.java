package io.github.takenoha.towerdefense.persistence;

/** Physical-to-database handoff state for one crystal redemption operation. */
public enum ResearchCrystalRedemptionState {
    PREPARED,
    APPLIED,
    ROLLED_BACK
}
