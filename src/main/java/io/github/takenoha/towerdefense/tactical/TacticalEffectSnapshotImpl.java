package io.github.takenoha.towerdefense.tactical;

import io.github.takenoha.towerdefense.domain.TowerType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immutable, pre-aggregated tactical effects for one active defense. */
public final class TacticalEffectSnapshotImpl implements TacticalEffectSnapshot {
    private final EnumMap<TowerType, EnumMap<TacticalTargetCondition, Double>> damage;
    private final EnumMap<TowerType, EnumMap<TacticalTargetCondition, Double>> attackInterval;
    private final EnumMap<TowerType, Double> rangeAdd;
    private final EnumMap<TowerType, Double> areaRadius;
    private final EnumMap<TowerType, Integer> chainCount;
    private final EnumMap<TowerType, Double> slowStrength;
    private final EnumMap<TowerType, Double> burnDuration;
    private final double supportBuff;
    private final double repairCost;
    private final double towerDamageTaken;

    TacticalEffectSnapshotImpl(
            EnumMap<TowerType, EnumMap<TacticalTargetCondition, Double>> damage,
            EnumMap<TowerType, EnumMap<TacticalTargetCondition, Double>> attackInterval,
            EnumMap<TowerType, Double> rangeAdd,
            EnumMap<TowerType, Double> areaRadius,
            EnumMap<TowerType, Integer> chainCount,
            EnumMap<TowerType, Double> slowStrength,
            EnumMap<TowerType, Double> burnDuration,
            double supportBuff,
            double repairCost,
            double towerDamageTaken) {
        this.damage = copyConditional(damage);
        this.attackInterval = copyConditional(attackInterval);
        this.rangeAdd = new EnumMap<>(Objects.requireNonNull(rangeAdd, "rangeAdd"));
        this.areaRadius = new EnumMap<>(Objects.requireNonNull(areaRadius, "areaRadius"));
        this.chainCount = new EnumMap<>(Objects.requireNonNull(chainCount, "chainCount"));
        this.slowStrength = new EnumMap<>(Objects.requireNonNull(slowStrength, "slowStrength"));
        this.burnDuration = new EnumMap<>(Objects.requireNonNull(burnDuration, "burnDuration"));
        this.supportBuff = positiveFiniteOrNeutral(supportBuff);
        this.repairCost = positiveFiniteOrNeutral(repairCost);
        this.towerDamageTaken = positiveFiniteOrNeutral(towerDamageTaken);
    }

    @Override
    public double damageMultiplier(TowerType type, TacticalTargetContext target) {
        return conditionalValue(damage, type, target);
    }

    @Override
    public double attackIntervalMultiplier(TowerType type, TacticalTargetContext target) {
        return conditionalValue(attackInterval, type, target);
    }

    @Override
    public double rangeAdd(TowerType type) {
        return rangeAdd.getOrDefault(Objects.requireNonNull(type, "type"), 0.0d);
    }

    @Override
    public double areaRadiusMultiplier(TowerType type) {
        return areaRadius.getOrDefault(
                Objects.requireNonNull(type, "type"), 1.0d);
    }

    @Override
    public int chainCountAdd(TowerType type) {
        return chainCount.getOrDefault(Objects.requireNonNull(type, "type"), 0);
    }

    @Override
    public double slowStrengthMultiplier(TowerType type) {
        return slowStrength.getOrDefault(
                Objects.requireNonNull(type, "type"), 1.0d);
    }

    @Override
    public double burnDurationMultiplier(TowerType type) {
        return burnDuration.getOrDefault(
                Objects.requireNonNull(type, "type"), 1.0d);
    }

    @Override
    public double supportBuffMultiplier() {
        return supportBuff;
    }

    @Override
    public double repairCostMultiplier() {
        return repairCost;
    }

    @Override
    public double towerDamageTakenMultiplier() {
        return towerDamageTaken;
    }

    private static EnumMap<TowerType, EnumMap<TacticalTargetCondition, Double>> copyConditional(
            EnumMap<TowerType, EnumMap<TacticalTargetCondition, Double>> source) {
        Objects.requireNonNull(source, "source");
        EnumMap<TowerType, EnumMap<TacticalTargetCondition, Double>> copy =
                new EnumMap<>(TowerType.class);
        for (TowerType type : TowerType.values()) {
            EnumMap<TacticalTargetCondition, Double> row = source.get(type);
            copy.put(
                    type,
                    row == null
                            ? new EnumMap<>(TacticalTargetCondition.class)
                            : new EnumMap<>(row));
        }
        return copy;
    }

    private static double conditionalValue(
            Map<TowerType, ? extends Map<TacticalTargetCondition, Double>> values,
            TowerType type,
            TacticalTargetContext target) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(target, "target");
        Map<TacticalTargetCondition, Double> row = values.get(type);
        if (row == null || row.isEmpty()) {
            return 1.0d;
        }
        double result = 1.0d;
        for (Map.Entry<TacticalTargetCondition, Double> entry : row.entrySet()) {
            if (matches(entry.getKey(), target)) {
                result = safeProduct(result, entry.getValue());
            }
        }
        return positiveFiniteOrNeutral(result);
    }

    private static boolean matches(
            TacticalTargetCondition condition,
            TacticalTargetContext target) {
        return switch (condition) {
            case NONE -> true;
            case CORE_BELOW_50_PERCENT -> target.coreBelowHalf();
            case CORE_BELOW_30_PERCENT -> target.coreBelowThirtyPercent();
            case BOSS -> target.boss();
            case HIGH_HP -> target.targetHasHighHealth();
            case LOW_HP -> target.targetHasLowHealth();
            case SLOWED -> target.slowed();
            case BURNING -> target.burning();
        };
    }

    static double safeProduct(double left, double right) {
        if (!Double.isFinite(left) || !Double.isFinite(right)
                || left <= 0.0d || right <= 0.0d) {
            return 1.0d;
        }
        double result = left * right;
        return Double.isFinite(result) && result > 0.0d ? result : 1.0d;
    }

    static double positiveFiniteOrNeutral(double value) {
        return Double.isFinite(value) && value > 0.0d ? value : 1.0d;
    }
}
