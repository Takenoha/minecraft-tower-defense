package io.github.takenoha.towerdefense.runtime;

import java.util.Objects;

/** Paper-independent facts used to authorize one event-enemy block action. */
public record TerrainMutationInput(
        String currentMaterialKey,
        boolean currentInventoryHolder,
        boolean currentCore,
        String targetMaterialKey) {
    public TerrainMutationInput {
        currentMaterialKey = requireMaterialKey(currentMaterialKey, "currentMaterialKey");
        targetMaterialKey = requireMaterialKey(targetMaterialKey, "targetMaterialKey");
    }

    private static String requireMaterialKey(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
