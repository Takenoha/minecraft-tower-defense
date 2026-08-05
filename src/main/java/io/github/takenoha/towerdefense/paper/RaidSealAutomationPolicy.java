package io.github.takenoha.towerdefense.paper;

/** Pure policy helpers for paths that can otherwise bypass player-facing seal safeguards. */
public final class RaidSealAutomationPolicy {
    private RaidSealAutomationPolicy() {
    }

    public static boolean cancelRightClick(
            boolean validSeal, boolean rightClick) {
        return validSeal && rightClick;
    }

    public static boolean cancelCrafter(
            boolean pluginRecipe,
            boolean resultIsTemplate,
            boolean echoShardIngredient,
            boolean legacyEnderEyeIngredient) {
        return pluginRecipe || resultIsTemplate
                || echoShardIngredient
                || legacyEnderEyeIngredient;
    }
}
