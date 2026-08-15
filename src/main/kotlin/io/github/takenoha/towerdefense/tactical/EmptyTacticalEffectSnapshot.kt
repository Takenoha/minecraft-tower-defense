package io.github.takenoha.towerdefense.tactical

import io.github.takenoha.towerdefense.domain.TowerType

/** Neutral singleton returned when no active tactical build is known. */
class EmptyTacticalEffectSnapshot private constructor() : TacticalEffectSnapshot {
    override fun damageMultiplier(type: TowerType, target: TacticalTargetContext): Double = 1.0

    override fun attackIntervalMultiplier(type: TowerType, target: TacticalTargetContext): Double = 1.0

    override fun rangeAdd(type: TowerType): Double = 0.0

    override fun areaRadiusMultiplier(type: TowerType): Double = 1.0

    override fun chainCountAdd(type: TowerType): Int = 0

    override fun slowStrengthMultiplier(type: TowerType): Double = 1.0

    override fun burnDurationMultiplier(type: TowerType): Double = 1.0

    override fun supportBuffMultiplier(): Double = 1.0

    override fun repairCostMultiplier(): Double = 1.0

    override fun towerDamageTakenMultiplier(): Double = 1.0

    companion object {
        @JvmField
        val INSTANCE: EmptyTacticalEffectSnapshot = EmptyTacticalEffectSnapshot()
    }
}
