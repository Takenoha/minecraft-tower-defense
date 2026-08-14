package io.github.takenoha.towerdefense.tactical

import io.github.takenoha.towerdefense.domain.TowerType
import java.util.EnumMap
import java.util.EnumSet
import java.util.Objects

private val ALL_TOWERS: Set<TowerType> =
    java.util.Set.copyOf(EnumSet.allOf(TowerType::class.java)).toSet()

/** Compiles selected node snapshots into a constant-time hot-path effect view. */
class TacticalEffectCompiler {
    fun compile(selection: TacticalBuildSelectionView): TacticalEffectSnapshot {
        Objects.requireNonNull(selection, "selection")
        val builder = Builder(selection.highestUnlockedTier())
        for (node in selection.nodes()) {
            val unlocked = if (selection.unlockedNodeIds().isEmpty()) {
                node.tier() <= selection.highestUnlockedTier()
            } else {
                selection.unlockedNodeIds().contains(node.id())
            }
            if (unlocked) {
                builder.addNode(node)
            }
        }
        return builder.build()
    }

    private class Builder(private val highestTier: Int) {
        private val damage = conditionalMap()
        private val attackInterval = conditionalMap()
        private val rangeAdd = neutralDoubleMap(0.0)
        private val areaRadius = neutralDoubleMap(1.0)
        private val chainCount = neutralIntegerMap()
        private val slowStrength = neutralDoubleMap(1.0)
        private val burnDuration = neutralDoubleMap(1.0)
        private var supportBuff = 1.0
        private var repairCost = 1.0
        private var towerDamageTaken = 1.0

        fun addNode(node: TacticalSkillNodeSnapshot) {
            if (node.tier() > highestTier) {
                return
            }
            for (effect in node.effects()) {
                addEffect(effect)
            }
        }

        private fun addEffect(effect: TacticalEffectEntry) {
            val value = boundedValue(effect)
            if (!value.isFinite()) {
                return
            }
            val targets: Set<TowerType> = if (effect.towerTypes().isEmpty()) {
                ALL_TOWERS
            } else {
                effect.towerTypes().toSet()
            }
            val condition = derivedCondition(effect)
            when (effect.type()) {
                TacticalEffectType.DAMAGE_MULTIPLIER ->
                    addMultiplier(damage, targets, condition, value)
                TacticalEffectType.ATTACK_INTERVAL_MULTIPLIER ->
                    addMultiplier(attackInterval, targets, condition, value)
                TacticalEffectType.RANGE_ADD -> addRange(targets, value)
                TacticalEffectType.AREA_RADIUS_MULTIPLIER ->
                    addPerTowerMultiplier(areaRadius, targets, value)
                TacticalEffectType.CHAIN_COUNT_ADD -> addChainCount(targets, value)
                TacticalEffectType.SLOW_STRENGTH_MULTIPLIER ->
                    addPerTowerMultiplier(slowStrength, targets, value)
                TacticalEffectType.BURN_DURATION_MULTIPLIER ->
                    addPerTowerMultiplier(burnDuration, targets, value)
                TacticalEffectType.SUPPORT_BUFF_MULTIPLIER -> {
                    if (targets.contains(TowerType.SUPPORT)) {
                        supportBuff = multiply(supportBuff, value)
                    }
                }
                TacticalEffectType.REPAIR_COST_MULTIPLIER -> {
                    if (targets == ALL_TOWERS) {
                        repairCost = multiply(repairCost, value)
                    }
                }
                TacticalEffectType.CORE_LOW_HP_DAMAGE_MULTIPLIER ->
                    addMultiplier(damage, targets, condition, value)
                TacticalEffectType.CORE_LOW_HP_ATTACK_INTERVAL_MULTIPLIER ->
                    addMultiplier(attackInterval, targets, condition, value)
                TacticalEffectType.TOWER_DAMAGE_TAKEN_MULTIPLIER -> {
                    if (targets == ALL_TOWERS) {
                        towerDamageTaken = multiply(towerDamageTaken, value)
                    }
                }
                TacticalEffectType.DAMAGE_TO_BOSS_MULTIPLIER,
                TacticalEffectType.DAMAGE_TO_HIGH_HP_MULTIPLIER,
                TacticalEffectType.DAMAGE_TO_LOW_HP_MULTIPLIER,
                TacticalEffectType.DAMAGE_TO_SLOWED_TARGET_MULTIPLIER,
                TacticalEffectType.DAMAGE_TO_BURNING_TARGET_MULTIPLIER ->
                    addMultiplier(damage, targets, condition, value)
            }
        }

        fun build(): TacticalEffectSnapshot = TacticalEffectSnapshotImpl(
            damage,
            attackInterval,
            rangeAdd,
            areaRadius,
            chainCount,
            slowStrength,
            burnDuration,
            supportBuff,
            repairCost,
            towerDamageTaken,
        )

        private fun addMultiplier(
            values: EnumMap<TowerType, EnumMap<TacticalTargetCondition, Double>>,
            targets: Set<TowerType>,
            condition: TacticalTargetCondition,
            value: Double,
        ) {
            if (value <= 0.0) {
                return
            }
            for (type in targets) {
                val row = values[type]!!
                row[condition] = multiply(row.getOrDefault(condition, 1.0), value)
            }
        }

        private fun addRange(targets: Set<TowerType>, value: Double) {
            if (value < 0.0) {
                return
            }
            for (type in targets) {
                val previous = rangeAdd.getOrDefault(type, 0.0)
                rangeAdd[type] = previous + value
            }
        }

        private fun addPerTowerMultiplier(
            values: EnumMap<TowerType, Double>,
            targets: Set<TowerType>,
            value: Double,
        ) {
            if (value <= 0.0) {
                return
            }
            for (type in targets) {
                values[type] = multiply(values.getOrDefault(type, 1.0), value)
            }
        }

        private fun addChainCount(targets: Set<TowerType>, value: Double) {
            if (value < 0.0 || value > Int.MAX_VALUE || value != Math.rint(value)) {
                return
            }
            val delta = value.toInt()
            for (type in targets) {
                val sum = chainCount.getOrDefault(type, 0).toLong() + delta.toLong()
                chainCount[type] = if (sum > Int.MAX_VALUE) Int.MAX_VALUE else sum.toInt()
            }
        }

        private fun boundedValue(effect: TacticalEffectEntry): Double {
            var value = effect.value()
            val minimum = effect.minimum()
            if (minimum != null) {
                value = maxOf(value, minimum)
            }
            val maximum = effect.maximum()
            if (maximum != null) {
                value = minOf(value, maximum)
            }
            return value
        }

        private fun derivedCondition(effect: TacticalEffectEntry): TacticalTargetCondition {
            if (effect.condition() != TacticalTargetCondition.NONE) {
                return effect.condition()
            }
            return when (effect.type()) {
                TacticalEffectType.CORE_LOW_HP_DAMAGE_MULTIPLIER ->
                    TacticalTargetCondition.CORE_BELOW_50_PERCENT
                TacticalEffectType.CORE_LOW_HP_ATTACK_INTERVAL_MULTIPLIER ->
                    TacticalTargetCondition.CORE_BELOW_30_PERCENT
                TacticalEffectType.DAMAGE_TO_BOSS_MULTIPLIER -> TacticalTargetCondition.BOSS
                TacticalEffectType.DAMAGE_TO_HIGH_HP_MULTIPLIER -> TacticalTargetCondition.HIGH_HP
                TacticalEffectType.DAMAGE_TO_LOW_HP_MULTIPLIER -> TacticalTargetCondition.LOW_HP
                TacticalEffectType.DAMAGE_TO_SLOWED_TARGET_MULTIPLIER -> TacticalTargetCondition.SLOWED
                TacticalEffectType.DAMAGE_TO_BURNING_TARGET_MULTIPLIER ->
                    TacticalTargetCondition.BURNING
                else -> TacticalTargetCondition.NONE
            }
        }

        private fun multiply(left: Double, right: Double): Double =
            TacticalEffectSnapshotImpl.safeProduct(left, right)

        private fun conditionalMap(): EnumMap<
            TowerType,
            EnumMap<TacticalTargetCondition, Double>,
        > {
            val map = EnumMap<TowerType, EnumMap<TacticalTargetCondition, Double>>(
                TowerType::class.java,
            )
            for (type in TowerType.values()) {
                map[type] = EnumMap(TacticalTargetCondition::class.java)
            }
            return map
        }

        private fun neutralDoubleMap(value: Double): EnumMap<TowerType, Double> {
            val map = EnumMap<TowerType, Double>(TowerType::class.java)
            for (type in TowerType.values()) {
                map[type] = value
            }
            return map
        }

        private fun neutralIntegerMap(): EnumMap<TowerType, Int> {
            val map = EnumMap<TowerType, Int>(TowerType::class.java)
            for (type in TowerType.values()) {
                map[type] = 0
            }
            return map
        }
    }
}
