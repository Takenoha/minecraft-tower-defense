package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

final class CoreMaterialPolicyTest {
    @Test
    void acceptsOnlyPluginOwnedCurrentOrLegacyCoreItemMaterials() {
        assertTrue(CoreMaterialPolicy.isCoreItemMaterial(Material.RESIN_BRICKS));
        assertTrue(CoreMaterialPolicy.isCoreItemMaterial(Material.NETHER_STAR));
        assertFalse(CoreMaterialPolicy.isCoreItemMaterial(Material.BEACON));
        assertFalse(CoreMaterialPolicy.isCoreItemMaterial(Material.DRIED_KELP_BLOCK));
    }

    @Test
    void identifiesLegacyAndCurrentPlacedMaterialsSeparately() {
        assertTrue(CoreMaterialPolicy.isLegacyItemMaterial(Material.NETHER_STAR));
        assertTrue(CoreMaterialPolicy.isCurrentBlock(Material.DRIED_KELP_BLOCK));
        assertTrue(CoreMaterialPolicy.isCoreBlockMaterial(Material.BEACON));
        assertTrue(CoreMaterialPolicy.isCoreBlockMaterial(Material.DRIED_KELP_BLOCK));
        assertFalse(CoreMaterialPolicy.isCoreBlockMaterial(Material.RESIN_BRICKS));
    }
}
