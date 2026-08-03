package io.github.takenoha.towerdefense.runtime;

import java.util.Locale;
import java.util.Objects;

/** One conservative, one-block builder placement selected from a world snapshot. */
public record EnemyBridgePlan(String targetMaterialKey) {
    public EnemyBridgePlan {
        Objects.requireNonNull(targetMaterialKey, "targetMaterialKey");
        if (targetMaterialKey.isBlank()) {
            throw new IllegalArgumentException("targetMaterialKey must not be blank");
        }
        String normalized = targetMaterialKey.toLowerCase(Locale.ROOT);
        if (normalized.equals("minecraft:air")
                || normalized.equals("minecraft:cave_air")
                || normalized.equals("minecraft:void_air")) {
            throw new IllegalArgumentException("a bridge target must not be air");
        }
        if (TerrainMutationPolicy.isRequiredMaterial(targetMaterialKey)) {
            throw new IllegalArgumentException(
                    "a bridge target must not be a required protected material");
        }
    }
}
