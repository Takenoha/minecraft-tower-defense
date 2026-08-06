package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.domain.TowerType;
import io.github.takenoha.towerdefense.domain.TowerTargetPriority;
import java.util.Objects;
import java.util.UUID;

/** Stable identity carried by one uninstalled tower item. */
public record TowerItemIdentity(
        UUID towerId,
        TowerType type,
        int individualLevel,
        TowerTargetPriority targetPriority) {
    public TowerItemIdentity {
        Objects.requireNonNull(towerId, "towerId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(targetPriority, "targetPriority");
        if (individualLevel <= 0) {
            throw new IllegalArgumentException("individualLevel must be positive");
        }
    }

    /** Backward-compatible constructor for items using the default target priority. */
    public TowerItemIdentity(UUID towerId, TowerType type, int individualLevel) {
        this(towerId, type, individualLevel, TowerTargetPriority.CORE_NEAREST);
    }
}
