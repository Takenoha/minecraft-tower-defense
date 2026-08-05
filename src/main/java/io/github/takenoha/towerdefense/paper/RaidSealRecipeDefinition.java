package io.github.takenoha.towerdefense.paper;

import java.util.List;

/** Vanilla ingredients shared by every stage-specific raid-seal recipe. */
public final class RaidSealRecipeDefinition {
    public static final String PAPER_MATERIAL = "PAPER";

    private RaidSealRecipeDefinition() {
    }

    public static List<String> shape() {
        return List.of(" P ", "PSP", " P ");
    }
}
