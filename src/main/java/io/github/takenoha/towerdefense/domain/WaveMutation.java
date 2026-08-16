package io.github.takenoha.towerdefense.domain;

import java.util.Locale;
import java.util.Objects;

/** A selectable rule which changes one defense event's wave composition. */
public enum WaveMutation {
    /** Compatibility value for starts created before wave mutations existed. */
    NONE("none", "なし"),
    SWIFT("swift", "高速化"),
    FORTIFIED("fortified", "重装化"),
    REINFORCEMENTS("reinforcements", "増援");

    private final String id;
    private final String displayName;

    WaveMutation(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** Parses the stable config/command identifier. */
    public static WaveMutation fromId(String value) {
        Objects.requireNonNull(value, "value");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (WaveMutation mutation : values()) {
            if (mutation.id.equals(normalized)) {
                return mutation;
            }
        }
        throw new IllegalArgumentException("unknown wave mutation: " + value);
    }
}
