package io.github.takenoha.towerdefense.paper;

import java.util.Objects;

/** Material compatibility policy for the current and legacy raid-seal items. */
public final class RaidSealMaterialPolicy {
    public static final String CURRENT_MATERIAL = "ECHO_SHARD";
    public static final String LEGACY_MATERIAL = "ENDER_EYE";

    private RaidSealMaterialPolicy() {
    }

    public static boolean supports(String materialName) {
        Objects.requireNonNull(materialName, "materialName");
        return CURRENT_MATERIAL.equals(materialName) || LEGACY_MATERIAL.equals(materialName);
    }

    public static boolean isLegacy(String materialName) {
        Objects.requireNonNull(materialName, "materialName");
        return LEGACY_MATERIAL.equals(materialName);
    }
}
