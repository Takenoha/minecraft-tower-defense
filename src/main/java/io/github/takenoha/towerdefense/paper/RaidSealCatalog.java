package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.domain.StageWaveSchedule;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.LongStream;

/** Stage-specific vanilla crafting and core-GUI slots for raid seals. */
public final class RaidSealCatalog {
    /** The initial ten stages have distinct, discoverable vanilla recipes. */
    public static final long MAX_RECIPE_STAGE_LEVEL = 10L;

    private static final List<String> RECIPE_MATERIALS = List.of(
            "GOLD_INGOT",
            "DIAMOND",
            "EMERALD",
            "AMETHYST_SHARD",
            "PRISMARINE_CRYSTALS",
            "QUARTZ",
            "GLOWSTONE_DUST",
            "REDSTONE",
            "LAPIS_LAZULI",
            "NETHERITE_SCRAP");

    private RaidSealCatalog() {
    }

    public static List<Long> recipeStages() {
        return LongStream.rangeClosed(1L, MAX_RECIPE_STAGE_LEVEL)
                .boxed()
                .toList();
    }

    public static String ingredientNameFor(long stageLevel) {
        StageWaveSchedule.requireValidStageLevel(stageLevel);
        if (stageLevel > MAX_RECIPE_STAGE_LEVEL) {
            throw new IllegalArgumentException(
                    "no vanilla recipe is registered for stage " + stageLevel);
        }
        return RECIPE_MATERIALS.get(Math.toIntExact(stageLevel - 1L));
    }

    /** Maps the compact stage buttons in the 27-slot core GUI to their stage level. */
    public static OptionalLong stageAtSlot(int rawSlot) {
        if (rawSlot >= 1 && rawSlot <= 8) {
            return OptionalLong.of(rawSlot);
        }
        if (rawSlot == 16) {
            return OptionalLong.of(9L);
        }
        if (rawSlot == 17) {
            return OptionalLong.of(10L);
        }
        return OptionalLong.empty();
    }

    public static int slotForStage(long stageLevel) {
        if (stageLevel < 1L || stageLevel > MAX_RECIPE_STAGE_LEVEL) {
            throw new IllegalArgumentException("stage is outside the GUI catalog: " + stageLevel);
        }
        return stageLevel <= 8L ? Math.toIntExact(stageLevel) : Math.toIntExact(stageLevel + 7L);
    }
}
