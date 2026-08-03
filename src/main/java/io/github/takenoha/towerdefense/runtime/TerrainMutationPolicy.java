package io.github.takenoha.towerdefense.runtime;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Safety policy for one event-enemy block action.
 *
 * <p>The required protection set is deliberately code-owned. Configuration may add protections
 * later, but it must not be able to remove inventory, redstone, bed, core, or dangerous blocks.</p>
 */
public final class TerrainMutationPolicy {
    private static final Set<String> REQUIRED_MATERIALS = Set.of(
            "minecraft:barrier",
            "minecraft:bedrock",
            "minecraft:command_block",
            "minecraft:chain_command_block",
            "minecraft:repeating_command_block",
            "minecraft:structure_block",
            "minecraft:structure_void",
            "minecraft:jigsaw",
            "minecraft:light",
            "minecraft:spawner",
            "minecraft:end_portal",
            "minecraft:end_portal_frame",
            "minecraft:end_gateway",
            "minecraft:nether_portal",
            "minecraft:reinforced_deepslate",
            "minecraft:tnt",
            "minecraft:respawn_anchor",
            "minecraft:lava",
            "minecraft:water",
            "minecraft:fire",
            "minecraft:soul_fire");

    private final boolean enabled;

    public TerrainMutationPolicy(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public TerrainMutationDecision decide(TerrainMutationInput input) {
        Objects.requireNonNull(input, "input");
        if (!enabled) {
            return TerrainMutationDecision.DISABLED;
        }
        if (input.currentCore()
                || input.currentInventoryHolder()
                || isRequiredMaterial(input.currentMaterialKey())
                || isRequiredMaterial(input.targetMaterialKey())) {
            return TerrainMutationDecision.PROTECTED;
        }
        return TerrainMutationDecision.ALLOW;
    }

    public static boolean isRequiredMaterial(String materialKey) {
        Objects.requireNonNull(materialKey, "materialKey");
        String normalized = materialKey.toLowerCase(Locale.ROOT);
        return REQUIRED_MATERIALS.contains(normalized)
                || hasMaterialSuffix(normalized, "bed")
                || hasMaterialSuffix(normalized, "button")
                || hasMaterialSuffix(normalized, "pressure_plate")
                || hasMaterialSuffix(normalized, "shulker_box")
                || hasMaterialSuffix(normalized, "chest")
                || normalized.contains("redstone")
                || normalized.contains("piston")
                || normalized.contains("dispenser")
                || normalized.contains("dropper")
                || normalized.contains("hopper")
                || normalized.contains("observer")
                || normalized.contains("repeater")
                || normalized.contains("comparator")
                || normalized.contains("tripwire")
                || normalized.contains("daylight_detector")
                || normalized.contains("sculk_sensor")
                || hasMaterialSuffix(normalized, "furnace")
                || hasMaterialSuffix(normalized, "barrel")
                || hasMaterialSuffix(normalized, "brewing_stand")
                || hasMaterialSuffix(normalized, "beacon")
                || hasMaterialSuffix(normalized, "conduit")
                || hasMaterialSuffix(normalized, "smoker")
                || hasMaterialSuffix(normalized, "lectern")
                || hasMaterialSuffix(normalized, "campfire");
    }

    private static boolean hasMaterialSuffix(String materialKey, String suffix) {
        return materialKey.endsWith(":" + suffix) || materialKey.endsWith("_" + suffix);
    }
}
