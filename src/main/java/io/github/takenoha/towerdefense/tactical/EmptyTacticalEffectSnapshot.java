package io.github.takenoha.towerdefense.tactical;

import io.github.takenoha.towerdefense.domain.TowerType;

/** Neutral singleton returned when no active tactical build is known. */
public final class EmptyTacticalEffectSnapshot implements TacticalEffectSnapshot {
    public static final EmptyTacticalEffectSnapshot INSTANCE = new EmptyTacticalEffectSnapshot();

    private EmptyTacticalEffectSnapshot() {
    }

    @Override
    public double damageMultiplier(TowerType type, TacticalTargetContext target) {
        return 1.0d;
    }

    @Override
    public double attackIntervalMultiplier(TowerType type, TacticalTargetContext target) {
        return 1.0d;
    }

    @Override
    public double rangeAdd(TowerType type) {
        return 0.0d;
    }

    @Override
    public double areaRadiusMultiplier(TowerType type) {
        return 1.0d;
    }

    @Override
    public int chainCountAdd(TowerType type) {
        return 0;
    }

    @Override
    public double slowStrengthMultiplier(TowerType type) {
        return 1.0d;
    }

    @Override
    public double burnDurationMultiplier(TowerType type) {
        return 1.0d;
    }

    @Override
    public double supportBuffMultiplier() {
        return 1.0d;
    }

    @Override
    public double repairCostMultiplier() {
        return 1.0d;
    }

    @Override
    public double towerDamageTakenMultiplier() {
        return 1.0d;
    }
}
