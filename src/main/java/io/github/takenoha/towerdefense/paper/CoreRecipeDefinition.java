package io.github.takenoha.towerdefense.paper;

import java.util.List;

/** Vanilla ingredients for the public core item recipe. */
public final class CoreRecipeDefinition {
    public static final String DIAMOND_BLOCK_MATERIAL = "DIAMOND_BLOCK";
    public static final String IRON_INGOT_MATERIAL = "IRON_INGOT";

    private CoreRecipeDefinition() {
    }

    public static List<String> shape() {
        return List.of(" I ", "IDI", " I ");
    }
}
