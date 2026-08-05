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
    void crafterPolicyClosesPluginTemplatesAndSealMaterials() {
        assertTrue(RaidSealAutomationPolicy.cancelCrafter(true, false, false, false));
        assertTrue(RaidSealAutomationPolicy.cancelCrafter(false, true, false, false));
        assertTrue(RaidSealAutomationPolicy.cancelCrafter(false, false, true, false));
        assertTrue(RaidSealAutomationPolicy.cancelCrafter(false, false, false, true));
        assertFalse(RaidSealAutomationPolicy.cancelCrafter(false, false, false, false));
    }
}
