package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class CoreRecipeDefinitionTest {
    @Test
    void usesDiamondBlockAndFourIronIngots() {
        assertEquals(List.of(" I ", "IDI", " I "), CoreRecipeDefinition.shape());
        assertEquals("DIAMOND_BLOCK", CoreRecipeDefinition.DIAMOND_BLOCK_MATERIAL);
        assertEquals("IRON_INGOT", CoreRecipeDefinition.IRON_INGOT_MATERIAL);
    }
}
