package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class RaidSealCatalogTest {
    @Test
    void exposesTenStageRecipes() {
        assertEquals(10, RaidSealCatalog.recipeStages().size());
        assertEquals("GOLD_INGOT", RaidSealCatalog.ingredientNameFor(1L));
        assertEquals("NETHERITE_SCRAP", RaidSealCatalog.ingredientNameFor(10L));
    }

    @Test
    void mapsAllRecipeStagesToNonConflictingCoreGuiSlots() {
        for (long stage = 1L; stage <= RaidSealCatalog.MAX_RECIPE_STAGE_LEVEL; stage++) {
            int slot = RaidSealCatalog.slotForStage(stage);
            assertEquals(OptionalLong.of(stage), RaidSealCatalog.stageAtSlot(slot));
        }
    }

    @Test
    void mapsTheFirstTenStagesToUniqueSlots() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RaidSealCatalog.ingredientNameFor(11L));
    }
}
