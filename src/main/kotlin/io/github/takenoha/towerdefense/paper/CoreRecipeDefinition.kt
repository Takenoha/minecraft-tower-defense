package io.github.takenoha.towerdefense.paper

/** Vanilla ingredients for the public core item recipe. */
class CoreRecipeDefinition private constructor() {
    companion object {
        const val DIAMOND_BLOCK_MATERIAL: String = "DIAMOND_BLOCK"
        const val IRON_INGOT_MATERIAL: String = "IRON_INGOT"

        @JvmStatic
        fun shape(): List<String> = listOf(" I ", "IDI", " I ")
    }
}
