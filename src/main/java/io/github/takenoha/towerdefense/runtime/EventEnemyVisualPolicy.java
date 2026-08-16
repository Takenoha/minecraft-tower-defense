package io.github.takenoha.towerdefense.runtime;

import io.github.takenoha.towerdefense.domain.EnemyRole;
import java.util.Objects;

/** Visual policy for enemies owned by an active defense event. */
public final class EventEnemyVisualPolicy {
    private EventEnemyVisualPolicy() {
    }

    /** Every persisted defense-event role is intentionally visible through glowing. */
    public static boolean shouldGlow(EnemyRole role) {
        Objects.requireNonNull(role, "role");
        return switch (role) {
            case NORMAL, DESTROYER, BUILDER, BOSS, SPEEDSTER, RANGED, HEAVY, SUPPORT -> true;
        };
    }
}
