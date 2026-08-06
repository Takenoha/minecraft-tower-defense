package io.github.takenoha.towerdefense.tactical;

import io.github.takenoha.towerdefense.domain.TowerType;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Immutable effect data stored inside a selected node snapshot. */
public record TacticalEffectEntry(
        TacticalEffectType type,
        Set<TowerType> towerTypes,
        double value,
        TacticalTargetCondition condition,
        Double minimum,
        Double maximum) {
    public TacticalEffectEntry {
        type = Objects.requireNonNull(type, "type");
        condition = Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(towerTypes, "towerTypes");
        EnumSet<TowerType> copy = towerTypes.isEmpty()
                ? EnumSet.noneOf(TowerType.class)
                : EnumSet.copyOf(towerTypes);
        towerTypes = Collections.unmodifiableSet(copy);
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("value must be finite");
        }
        if (minimum != null && !Double.isFinite(minimum)) {
            throw new IllegalArgumentException("minimum must be finite");
        }
        if (maximum != null && !Double.isFinite(maximum)) {
            throw new IllegalArgumentException("maximum must be finite");
        }
        if (minimum != null && maximum != null && minimum > maximum) {
            throw new IllegalArgumentException("minimum must not exceed maximum");
        }
    }
}
