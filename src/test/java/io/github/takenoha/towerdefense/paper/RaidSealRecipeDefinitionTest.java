package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class RaidSealRecipeDefinitionTest {
    @Test
    void usesPaperFourAndOneStageMaterial() {
        assertEquals(List.of(" P ", "PSP", " P "), RaidSealRecipeDefinition.shape());
        assertEquals("PAPER", RaidSealRecipeDefinition.PAPER_MATERIAL);
    }
}
