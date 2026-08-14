package io.github.takenoha.towerdefense.paper

/** Pure policy helpers for paths that can otherwise bypass player-facing seal safeguards. */
class RaidSealAutomationPolicy private constructor() {
    companion object {
        @JvmStatic
        fun cancelRightClick(validSeal: Boolean, rightClick: Boolean): Boolean =
            validSeal && rightClick

        @JvmStatic
        fun cancelCrafter(
            pluginRecipe: Boolean,
            resultIsTemplate: Boolean,
            currentSealIngredient: Boolean,
            legacySealIngredient: Boolean,
        ): Boolean =
            pluginRecipe || resultIsTemplate || currentSealIngredient || legacySealIngredient
    }
}
