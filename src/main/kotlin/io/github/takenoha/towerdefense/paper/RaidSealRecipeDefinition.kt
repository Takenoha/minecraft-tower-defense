package io.github.takenoha.towerdefense.paper

/** Vanilla ingredients shared by every stage-specific raid-seal recipe. */
class RaidSealRecipeDefinition private constructor() {
    companion object {
        const val PAPER_MATERIAL: String = "PAPER"

        @JvmStatic
        fun shape(): List<String> = listOf(" P ", "PSP", " P ")
    }
}
