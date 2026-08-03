package io.github.takenoha.towerdefense.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class TerrainMutationPolicyTest {
    @Test
    void remainsDisabledUntilNormalSettlementIsImplemented() {
        TerrainMutationPolicy policy = new TerrainMutationPolicy(false);

        assertEquals(
                TerrainMutationDecision.DISABLED,
                policy.decide(new TerrainMutationInput(
                        "minecraft:stone", false, false, "minecraft:air")));
    }

    @Test
    void requiredProtectionCannotBeBypassedByAllowingTheAction() {
        TerrainMutationPolicy policy = new TerrainMutationPolicy(true);

        assertEquals(
                TerrainMutationDecision.PROTECTED,
                policy.decide(new TerrainMutationInput(
                        "minecraft:stone", true, false, "minecraft:air")));
        assertEquals(
                TerrainMutationDecision.PROTECTED,
                policy.decide(new TerrainMutationInput(
                        "minecraft:stone", false, true, "minecraft:air")));
        assertEquals(
                TerrainMutationDecision.PROTECTED,
                policy.decide(new TerrainMutationInput(
                        "minecraft:stone", false, false, "minecraft:oak_bed")));
        assertEquals(
                TerrainMutationDecision.PROTECTED,
                policy.decide(new TerrainMutationInput(
                        "minecraft:stone", false, false, "minecraft:hopper")));
        assertEquals(
                TerrainMutationDecision.PROTECTED,
                policy.decide(new TerrainMutationInput(
                        "minecraft:oak_sign", false, false, true, "minecraft:air")));
    }

    @Test
    void ordinaryBlockActionsAreAllowedOnlyWhenExplicitlyEnabled() {
        TerrainMutationPolicy policy = new TerrainMutationPolicy(true);

        assertEquals(
                TerrainMutationDecision.ALLOW,
                policy.decide(new TerrainMutationInput(
                        "minecraft:stone", false, false, "minecraft:air")));
        assertTrue(TerrainMutationPolicy.isRequiredMaterial("minecraft:oak_button"));
        assertTrue(TerrainMutationPolicy.isRequiredMaterial("minecraft:oak_pressure_plate"));
        assertTrue(TerrainMutationPolicy.isRequiredMaterial("minecraft:chest"));
        assertTrue(TerrainMutationPolicy.isRequiredMaterial("minecraft:smoker"));
    }
}
