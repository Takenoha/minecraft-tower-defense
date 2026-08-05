package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RaidSealAutomationPolicyTest {
    @Test
    void validSealRightClickIsCancelledFromBothHands() {
        assertTrue(RaidSealAutomationPolicy.cancelRightClick(true, true));
        assertTrue(RaidSealAutomationPolicy.cancelRightClick(true, true));
        assertFalse(RaidSealAutomationPolicy.cancelRightClick(false, true));
        assertFalse(RaidSealAutomationPolicy.cancelRightClick(true, false));
    }

    @Test
    void crafterPolicyClosesPluginRecipesAndTemplates() {
        assertTrue(RaidSealAutomationPolicy.cancelCrafter(true, false, false, false));
        assertTrue(RaidSealAutomationPolicy.cancelCrafter(false, true, false, false));
    }

    @Test
    void crafterPolicyClosesNewAndLegacyTaggedSealIngredients() {
        assertTrue(RaidSealAutomationPolicy.cancelCrafter(false, false, true, false));
        assertTrue(RaidSealAutomationPolicy.cancelCrafter(false, false, false, true));
    }

    @Test
    void untaggedEchoShardAndEnderEyeRemainAllowed() {
        assertFalse(RaidSealAutomationPolicy.cancelCrafter(false, false, false, false));
    }
}
