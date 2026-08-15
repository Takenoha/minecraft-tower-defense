package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.domain.StageWaveSchedule
import java.util.ArrayList
import java.util.Collections
import java.util.OptionalLong

/** Stage-specific vanilla crafting and core-GUI slots for raid seals. */
class RaidSealCatalog private constructor() {
    companion object {
        /** The initial ten stages have distinct, discoverable vanilla recipes. */
        const val MAX_RECIPE_STAGE_LEVEL: Long = 10L

        private val RECIPE_MATERIALS = listOf(
            "GOLD_INGOT",
            "DIAMOND",
            "EMERALD",
            "AMETHYST_SHARD",
            "PRISMARINE_CRYSTALS",
            "QUARTZ",
            "GLOWSTONE_DUST",
            "REDSTONE",
            "LAPIS_LAZULI",
            "NETHERITE_SCRAP",
        )

        @JvmStatic
        fun recipeStages(): List<Long> = Collections.unmodifiableList(
            ArrayList((1L..MAX_RECIPE_STAGE_LEVEL).toList()),
        )

        @JvmStatic
        fun ingredientNameFor(stageLevel: Long): String {
            StageWaveSchedule.requireValidStageLevel(stageLevel)
            if (stageLevel > MAX_RECIPE_STAGE_LEVEL) {
                throw IllegalArgumentException(
                    "no vanilla recipe is registered for stage $stageLevel",
                )
            }
            return RECIPE_MATERIALS[Math.toIntExact(stageLevel - 1L)]
        }

        /** Maps the compact stage buttons in the 27-slot core GUI to their stage level. */
        @JvmStatic
        fun stageAtSlot(rawSlot: Int): OptionalLong = when {
            rawSlot in 1..8 -> OptionalLong.of(rawSlot.toLong())
            rawSlot == 16 -> OptionalLong.of(9L)
            rawSlot == 17 -> OptionalLong.of(10L)
            else -> OptionalLong.empty()
        }

        @JvmStatic
        fun slotForStage(stageLevel: Long): Int {
            if (stageLevel < 1L || stageLevel > MAX_RECIPE_STAGE_LEVEL) {
                throw IllegalArgumentException("stage is outside the GUI catalog: $stageLevel")
            }
            return if (stageLevel <= 8L) {
                Math.toIntExact(stageLevel)
            } else {
                Math.toIntExact(stageLevel + 7L)
            }
        }
    }
}
