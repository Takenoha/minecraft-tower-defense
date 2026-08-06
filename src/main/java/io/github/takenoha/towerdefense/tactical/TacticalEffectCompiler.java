package io.github.takenoha.towerdefense.tactical;

import io.github.takenoha.towerdefense.domain.TowerType;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Compiles selected node snapshots into a constant-time hot-path effect view. */
public final class TacticalEffectCompiler {
    private static final Set<TowerType> ALL_TOWERS =
            Set.copyOf(EnumSet.allOf(TowerType.class));

    public TacticalEffectSnapshot compile(TacticalBuildSelectionView selection) {
        Objects.requireNonNull(selection, "selection");
        Builder builder = new Builder(selection.highestUnlockedTier());
        for (TacticalSkillNodeSnapshot node : selection.nodes()) {
            if (node.tier() <= selection.highestUnlockedTier()) {
                builder.addNode(node);
            }
        }
        return builder.build();
    }

    private static final class Builder {
        private final int highestTier;
        private final EnumMap<TowerType, EnumMap<TacticalTargetCondition, Double>> damage =
                conditionalMap();
        private final EnumMap<TowerType, EnumMap<TacticalTargetCondition, Double>> attackInterval =
                conditionalMap();
        private final EnumMap<TowerType, Double> rangeAdd = neutralDoubleMap(0.0d);
        private final EnumMap<TowerType, Double> areaRadius = neutralDoubleMap(1.0d);
        private final EnumMap<TowerType, Integer> chainCount = neutralIntegerMap();
        private final EnumMap<TowerType, Double> slowStrength = neutralDoubleMap(1.0d);
        private final EnumMap<TowerType, Double> burnDuration = neutralDoubleMap(1.0d);
        private double supportBuff = 1.0d;
        private double repairCost = 1.0d;
        private double towerDamageTaken = 1.0d;

        private Builder(int highestTier) {
            this.highestTier = highestTier;
        }

        private void addNode(TacticalSkillNodeSnapshot node) {
            if (node.tier() > highestTier) {
                return;
            }
            for (TacticalEffectEntry effect : node.effects()) {
                addEffect(effect);
            }
        }

        private void addEffect(TacticalEffectEntry effect) {
            double value = boundedValue(effect);
            if (!Double.isFinite(value)) {
                return;
            }
            Set<TowerType> targets = effect.towerTypes().isEmpty()
                    ? ALL_TOWERS
                    : effect.towerTypes();
            TacticalTargetCondition condition = derivedCondition(effect);
            switch (effect.type()) {
                case DAMAGE_MULTIPLIER -> addMultiplier(damage, targets, condition, value);
                case ATTACK_INTERVAL_MULTIPLIER ->
                        addMultiplier(attackInterval, targets, condition, value);
                case RANGE_ADD -> addRange(targets, value);
                case AREA_RADIUS_MULTIPLIER -> addPerTowerMultiplier(areaRadius, targets, value);
                case CHAIN_COUNT_ADD -> addChainCount(targets, value);
                case SLOW_STRENGTH_MULTIPLIER ->
                        addPerTowerMultiplier(slowStrength, targets, value);
                case BURN_DURATION_MULTIPLIER ->
                        addPerTowerMultiplier(burnDuration, targets, value);
                case SUPPORT_BUFF_MULTIPLIER -> {
                    if (targets.contains(TowerType.SUPPORT)) {
                        supportBuff = multiply(supportBuff, value);
                    }
                }
                case REPAIR_COST_MULTIPLIER -> {
                    if (targets.equals(ALL_TOWERS)) {
                        repairCost = multiply(repairCost, value);
                    }
                }
                case CORE_LOW_HP_DAMAGE_MULTIPLIER ->
                        addMultiplier(damage, targets, condition, value);
                case CORE_LOW_HP_ATTACK_INTERVAL_MULTIPLIER ->
                        addMultiplier(attackInterval, targets, condition, value);
                case TOWER_DAMAGE_TAKEN_MULTIPLIER -> {
                    if (targets.equals(ALL_TOWERS)) {
                        towerDamageTaken = multiply(towerDamageTaken, value);
                    }
                }
                case DAMAGE_TO_BOSS_MULTIPLIER,
                        DAMAGE_TO_HIGH_HP_MULTIPLIER,
                        DAMAGE_TO_LOW_HP_MULTIPLIER,
                        DAMAGE_TO_SLOWED_TARGET_MULTIPLIER,
                        DAMAGE_TO_BURNING_TARGET_MULTIPLIER ->
                        addMultiplier(damage, targets, condition, value);
            }
        }

        private TacticalEffectSnapshot build() {
            return new TacticalEffectSnapshotImpl(
                    damage,
                    attackInterval,
                    rangeAdd,
                    areaRadius,
                    chainCount,
                    slowStrength,
                    burnDuration,
                    supportBuff,
                    repairCost,
                    towerDamageTaken);
        }

        private static void addMultiplier(
                EnumMap<TowerType, EnumMap<TacticalTargetCondition, Double>> values,
                Set<TowerType> targets,
                TacticalTargetCondition condition,
                double value) {
            if (value <= 0.0d) {
                return;
            }
            for (TowerType type : targets) {
                EnumMap<TacticalTargetCondition, Double> row = values.get(type);
                row.put(condition, multiply(row.getOrDefault(condition, 1.0d), value));
            }
        }

        private void addRange(Set<TowerType> targets, double value) {
            if (value < 0.0d) {
                return;
            }
            for (TowerType type : targets) {
                double previous = rangeAdd.getOrDefault(type, 0.0d);
                rangeAdd.put(type, previous + value);
            }
        }

        private static void addPerTowerMultiplier(
                EnumMap<TowerType, Double> values,
                Set<TowerType> targets,
                double value) {
            if (value <= 0.0d) {
                return;
            }
            for (TowerType type : targets) {
                values.put(type, multiply(values.getOrDefault(type, 1.0d), value));
            }
        }

        private void addChainCount(Set<TowerType> targets, double value) {
            if (value < 0.0d || value > Integer.MAX_VALUE
                    || value != Math.rint(value)) {
                return;
            }
            int delta = (int) value;
            for (TowerType type : targets) {
                long sum = (long) chainCount.getOrDefault(type, 0) + delta;
                chainCount.put(type, sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum);
            }
        }

        private static double boundedValue(TacticalEffectEntry effect) {
            double value = effect.value();
            if (effect.minimum() != null) {
                value = Math.max(value, effect.minimum());
            }
            if (effect.maximum() != null) {
                value = Math.min(value, effect.maximum());
            }
            return value;
        }

        private static TacticalTargetCondition derivedCondition(TacticalEffectEntry effect) {
            if (effect.condition() != TacticalTargetCondition.NONE) {
                return effect.condition();
            }
            return switch (effect.type()) {
                case CORE_LOW_HP_DAMAGE_MULTIPLIER -> TacticalTargetCondition.CORE_BELOW_50_PERCENT;
                case CORE_LOW_HP_ATTACK_INTERVAL_MULTIPLIER ->
                        TacticalTargetCondition.CORE_BELOW_30_PERCENT;
                case DAMAGE_TO_BOSS_MULTIPLIER -> TacticalTargetCondition.BOSS;
                case DAMAGE_TO_HIGH_HP_MULTIPLIER -> TacticalTargetCondition.HIGH_HP;
                case DAMAGE_TO_LOW_HP_MULTIPLIER -> TacticalTargetCondition.LOW_HP;
                case DAMAGE_TO_SLOWED_TARGET_MULTIPLIER -> TacticalTargetCondition.SLOWED;
                case DAMAGE_TO_BURNING_TARGET_MULTIPLIER -> TacticalTargetCondition.BURNING;
                default -> TacticalTargetCondition.NONE;
            };
        }

        private static double multiply(double left, double right) {
            return TacticalEffectSnapshotImpl.safeProduct(left, right);
        }

        private static EnumMap<TowerType, EnumMap<TacticalTargetCondition, Double>> conditionalMap() {
            EnumMap<TowerType, EnumMap<TacticalTargetCondition, Double>> map =
                    new EnumMap<>(TowerType.class);
            for (TowerType type : TowerType.values()) {
                map.put(type, new EnumMap<>(TacticalTargetCondition.class));
            }
            return map;
        }

        private static EnumMap<TowerType, Double> neutralDoubleMap(double value) {
            EnumMap<TowerType, Double> map = new EnumMap<>(TowerType.class);
            for (TowerType type : TowerType.values()) {
                map.put(type, value);
            }
            return map;
        }

        private static EnumMap<TowerType, Integer> neutralIntegerMap() {
            EnumMap<TowerType, Integer> map = new EnumMap<>(TowerType.class);
            for (TowerType type : TowerType.values()) {
                map.put(type, 0);
            }
            return map;
        }
    }
}
