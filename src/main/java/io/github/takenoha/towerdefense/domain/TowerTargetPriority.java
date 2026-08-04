package io.github.takenoha.towerdefense.domain;

import java.util.Locale;
import java.util.Objects;

/** Stable target-selection modes persisted with each installed tower. */
public enum TowerTargetPriority {
    CORE_NEAREST("core_nearest", "コアに近い"),
    NEAREST("nearest", "距離が近い"),
    HEALTH_HIGH("health_high", "HPが高い"),
    HEALTH_LOW("health_low", "HPが低い"),
    BOSS("boss", "ボス優先");

    private final String id;
    private final String displayName;

    TowerTargetPriority(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public static TowerTargetPriority fromId(String id) {
        Objects.requireNonNull(id, "id");
        for (TowerTargetPriority priority : values()) {
            if (priority.id.equals(id.toLowerCase(Locale.ROOT))) {
                return priority;
            }
        }
        throw new IllegalArgumentException("Unknown tower target priority: " + id);
    }
}
