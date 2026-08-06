package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RaidSealMaterialPolicyTest {
    @Test
    void acceptsCurrentAndLegacyMaterialsOnly() {
        assertTrue(RaidSealMaterialPolicy.supports(RaidSealMaterialPolicy.CURRENT_MATERIAL));
        assertTrue(RaidSealMaterialPolicy.supports(RaidSealMaterialPolicy.LEGACY_MATERIAL));
        assertFalse(RaidSealMaterialPolicy.supports("ENDER_PEARL"));
        assertTrue(RaidSealMaterialPolicy.isLegacy(RaidSealMaterialPolicy.LEGACY_MATERIAL));
        assertFalse(RaidSealMaterialPolicy.isLegacy(RaidSealMaterialPolicy.CURRENT_MATERIAL));
    }
}
