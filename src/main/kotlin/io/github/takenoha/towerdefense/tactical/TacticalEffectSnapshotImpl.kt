package io.github.takenoha.towerdefense.tactical

import io.github.takenoha.towerdefense.domain.TowerType
import java.util.EnumMap
import java.util.Objects

/** Immutable, pre-aggregated tactical effects for one active defense. */
class TacticalEffectSnapshotImpl internal constructor(
    damage: EnumMap<TowerType, EnumMap<TacticalTargetCondition, Double>>,
    attackInterval: EnumMap<TowerType, EnumMap<TacticalTargetCondition, Double>>,
    rangeAdd: EnumMap<TowerType, Double>,
    areaRadius: EnumMap<TowerType, Double>,
    chainCount: EnumMap<TowerType, Int>,
    slowStrength: EnumMap<TowerType, Double>,
    burnDuration: EnumMap<TowerType, Double>,
    supportBuff: Double,
    repairCost: Double,
    towerDamageTaken: Double,
) : TacticalEffectSnapshot {
    private val damage = copyConditional(damage)
    private val attackInterval = copyConditional(attackInterval)
    private val rangeAdd = EnumMap(
        Objects.requireNonNull(rangeAdd, "rangeAdd"),
    )
    private val areaRadius = EnumMap(
        Objects.requireNonNull(areaRadius, "areaRadius"),
    )
    private val chainCount = EnumMap(
        Objects.requireNonNull(chainCount, "chainCount"),
    )
    private val slowStrength = EnumMap(
        Objects.requireNonNull(slowStrength, "slowStrength"),
    )
    private val burnDuration = EnumMap(
        Objects.requireNonNull(burnDuration, "burnDuration"),
    )
    private val supportBuff = positiveFiniteOrNeutral(supportBuff)
    private val repairCost = positiveFiniteOrNeutral(repairCost)
    private val towerDamageTaken = positiveFiniteOrNeutral(towerDamageTaken)

    override fun damageMultiplier(
        type: TowerType,
        target: TacticalTargetContext,
    ): Double = conditionalValue(damage, type, target)

    override fun attackIntervalMultiplier(
        type: TowerType,
        target: TacticalTargetContext,
    ): Double = conditionalValue(attackInterval, type, target)

    override fun rangeAdd(type: TowerType): Double = rangeAdd.getOrDefault(
        Objects.requireNonNull(type, "type"),
        0.0,
    )

    override fun areaRadiusMultiplier(type: TowerType): Double = areaRadius.getOrDefault(
        Objects.requireNonNull(type, "type"),
        1.0,
    )

    override fun chainCountAdd(type: TowerType): Int = chainCount.getOrDefault(
        Objects.requireNonNull(type, "type"),
        0,
    )

    override fun slowStrengthMultiplier(type: TowerType): Double = slowStrength.getOrDefault(
        Objects.requireNonNull(type, "type"),
        1.0,
    )

    override fun burnDurationMultiplier(type: TowerType): Double = burnDuration.getOrDefault(
        Objects.requireNonNull(type, "type"),
        1.0,
    )

    override fun supportBuffMultiplier(): Double = supportBuff

    override fun repairCostMultiplier(): Double = repairCost

    override fun towerDamageTakenMultiplier(): Double = towerDamageTaken

    companion object {
        private fun copyConditional(
            source: EnumMap<TowerType, EnumMap<TacticalTargetCondition, Double>>,
        ): EnumMap<TowerType, EnumMap<TacticalTargetCondition, Double>> {
            Objects.requireNonNull(source, "source")
            val copy = EnumMap<TowerType, EnumMap<TacticalTargetCondition, Double>>(
                TowerType::class.java,
            )
            for (type in TowerType.values()) {
                val row = source[type]
                copy[type] = row?.let {
                    EnumMap<TacticalTargetCondition, Double>(it)
                } ?: EnumMap(TacticalTargetCondition::class.java)
            }
            return copy
        }

        private fun conditionalValue(
            values: Map<TowerType, Map<TacticalTargetCondition, Double>>,
            type: TowerType,
            target: TacticalTargetContext,
        ): Double {
            Objects.requireNonNull(type, "type")
            Objects.requireNonNull(target, "target")
            val row = values[type]
            if (row == null || row.isEmpty()) {
                return 1.0
            }
            var result = 1.0
            for (entry in row.entries) {
                if (matches(entry.key, target)) {
                    result = safeProduct(result, entry.value)
                }
            }
            return positiveFiniteOrNeutral(result)
        }

        private fun matches(
            condition: TacticalTargetCondition,
            target: TacticalTargetContext,
        ): Boolean = when (condition) {
            TacticalTargetCondition.NONE -> true
            TacticalTargetCondition.CORE_BELOW_50_PERCENT -> target.coreBelowHalf()
            TacticalTargetCondition.CORE_BELOW_30_PERCENT -> target.coreBelowThirtyPercent()
            TacticalTargetCondition.BOSS -> target.boss()
            TacticalTargetCondition.HIGH_HP -> target.targetHasHighHealth()
            TacticalTargetCondition.LOW_HP -> target.targetHasLowHealth()
            TacticalTargetCondition.SLOWED -> target.slowed()
            TacticalTargetCondition.BURNING -> target.burning()
        }

        @JvmStatic
        fun safeProduct(left: Double, right: Double): Double {
            if (!left.isFinite() || !right.isFinite() || left <= 0.0 || right <= 0.0) {
                return 1.0
            }
            val result = left * right
            return if (result.isFinite() && result > 0.0) result else 1.0
        }

        private fun positiveFiniteOrNeutral(value: Double): Double =
            if (value.isFinite() && value > 0.0) value else 1.0
    }
}
