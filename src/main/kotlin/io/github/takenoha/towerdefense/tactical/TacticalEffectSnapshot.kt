package io.github.takenoha.towerdefense.tactical

import io.github.takenoha.towerdefense.domain.TowerType

/** Hot-path, already-compiled tactical modifiers for one active defense. */
interface TacticalEffectSnapshot {
    fun damageMultiplier(type: TowerType, target: TacticalTargetContext): Double

    fun attackIntervalMultiplier(type: TowerType, target: TacticalTargetContext): Double

    fun rangeAdd(type: TowerType): Double

    fun areaRadiusMultiplier(type: TowerType): Double

    fun chainCountAdd(type: TowerType): Int

    fun slowStrengthMultiplier(type: TowerType): Double

    fun burnDurationMultiplier(type: TowerType): Double

    fun supportBuffMultiplier(): Double

    fun repairCostMultiplier(): Double

    fun towerDamageTakenMultiplier(): Double
}
