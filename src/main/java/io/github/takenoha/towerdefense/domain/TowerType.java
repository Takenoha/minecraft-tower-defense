package io.github.takenoha.towerdefense.domain;

import java.util.Locale;
import java.util.Objects;

/** Stable tower kind identifiers stored in the item PDC and SQLite rows. */
public enum TowerType {
    ARROW("arrow", "アロー"),
    CANNON("cannon", "キャノン");

    private final String id;
    private final String displayName;

    TowerType(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public static TowerType fromId(String id) {
        Objects.requireNonNull(id, "id");
        for (TowerType type : values()) {
            if (type.id.equals(id.toLowerCase(Locale.ROOT))) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown tower type: " + id);
    }
}
