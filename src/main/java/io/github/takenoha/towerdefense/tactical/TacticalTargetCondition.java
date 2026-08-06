package io.github.takenoha.towerdefense.tactical;

/** Conditions that can be evaluated from a read-only combat target context. */
public enum TacticalTargetCondition {
    NONE,
    CORE_BELOW_50_PERCENT,
    CORE_BELOW_30_PERCENT,
    BOSS,
    HIGH_HP,
    LOW_HP,
    SLOWED,
    BURNING
}
