package io.github.takenoha.towerdefense.tactical;

import io.github.takenoha.towerdefense.domain.TowerType;

/** Hot-path, already-compiled tactical modifiers for one active defense. */
public interface TacticalEffectSnapshot {
    double damageMultiplier(TowerType type, TacticalTargetContext target);

    double attackIntervalMultiplier(TowerType type, TacticalTargetContext target);

    double rangeAdd(TowerType type);

    double areaRadiusMultiplier(TowerType type);

    int chainCountAdd(TowerType type);

    double slowStrengthMultiplier(TowerType type);

    double burnDurationMultiplier(TowerType type);

    double supportBuffMultiplier();

    double repairCostMultiplier();

    double towerDamageTakenMultiplier();
}
