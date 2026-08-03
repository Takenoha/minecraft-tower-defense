package io.github.takenoha.towerdefense.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.config.TerrainMutationSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TerrainMutationActivationGateTest {
    @Test
    void keepsThePolicyDisabledWhenTheOperatorHasNotRequestedIt() {
        TerrainMutationActivationGate gate = new TerrainMutationActivationGate(
                new TerrainMutationSettings(false, true, true));

        assertFalse(gate.enabled());
        assertEquals(List.of("requested=false"), gate.blockers());
        assertEquals("disabled(requested=false)", gate.status());
    }

    @Test
    void keepsThePolicyDisabledWhenRecoveryEvidenceIsMissing() {
        TerrainMutationActivationGate gate = new TerrainMutationActivationGate(
                new TerrainMutationSettings(true, true, false));

        assertFalse(gate.enabled());
        assertEquals(List.of("recovery-verified=false"), gate.blockers());
    }

    @Test
    void enablesOnlyWhenAllIndependentInputsAreTrue() {
        TerrainMutationActivationGate gate = new TerrainMutationActivationGate(
                new TerrainMutationSettings(true, true, true));

        assertTrue(gate.enabled());
        assertEquals(List.of(), gate.blockers());
        assertEquals("enabled", gate.status());
    }
}
