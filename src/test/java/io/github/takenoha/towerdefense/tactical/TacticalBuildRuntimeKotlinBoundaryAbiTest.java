package io.github.takenoha.towerdefense.tactical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TacticalBuildRuntimeKotlinBoundaryAbiTest {
    @Test
    void keepsConstructorsInterfaceAndPublicMethods() throws Exception {
        assertTrue(Modifier.isPublic(TacticalBuildRuntime.class.getModifiers()));
        assertTrue(Modifier.isFinal(TacticalBuildRuntime.class.getModifiers()));
        assertTrue(TacticalEffectSnapshotProvider.class.isAssignableFrom(TacticalBuildRuntime.class));

        assertNotNull(TacticalBuildRuntime.class.getConstructor(
                TacticalBuildLifecycle.class,
                TacticalBuildStateProvider.class));
        assertNotNull(TacticalBuildRuntime.class.getConstructor(
                TacticalBuildLifecycle.class,
                TacticalEffectCache.class));

        var disabled = TacticalBuildRuntime.class.getMethod("disabled");
        assertTrue(Modifier.isPublic(disabled.getModifiers()));
        assertTrue(Modifier.isStatic(disabled.getModifiers()));
        assertEquals(TacticalBuildRuntime.class, disabled.getReturnType());

        assertEquals(TacticalUnlockResult.class, TacticalBuildRuntime.class.getMethod(
                "activateAtPreparation", UUID.class, UUID.class).getReturnType());
        assertEquals(TacticalUnlockResult.class, TacticalBuildRuntime.class.getMethod(
                "advanceAfterWave", UUID.class, int.class, int.class, UUID.class).getReturnType());
        assertEquals(TacticalUnlockResult.class, TacticalBuildRuntime.class.getMethod(
                "activateFinalTier", UUID.class, UUID.class).getReturnType());
        assertEquals(void.class, TacticalBuildRuntime.class.getMethod(
                "rebuild", UUID.class).getReturnType());
        assertEquals(void.class, TacticalBuildRuntime.class.getMethod(
                "invalidate", UUID.class).getReturnType());
        assertEquals(void.class, TacticalBuildRuntime.class.getMethod(
                "markTerminal", UUID.class, TacticalTerminalResult.class, UUID.class).getReturnType());
        assertEquals(TacticalEffectSnapshot.class, TacticalBuildRuntime.class.getMethod(
                "currentForDefense", UUID.class).getReturnType());
        assertEquals(TacticalEffectCache.class, TacticalBuildRuntime.class.getMethod(
                "effects").getReturnType());
    }

    @Test
    void preservesDisabledRuntimeNeutralBehavior() {
        var defenseId = UUID.randomUUID();
        var operationId = UUID.randomUUID();
        var runtime = TacticalBuildRuntime.disabled();

        assertEquals(0, runtime.activateAtPreparation(defenseId, operationId).highestUnlockedTier());
        assertEquals(0, runtime.advanceAfterWave(defenseId, 1, 10, operationId)
                .highestUnlockedTier());
        assertEquals(0, runtime.activateFinalTier(defenseId, operationId).highestUnlockedTier());
        assertEquals(0, runtime.effects().size());
        assertNotNull(runtime.currentForDefense(defenseId));

        runtime.markTerminal(defenseId, TacticalTerminalResult.VICTORY, operationId);
        runtime.invalidate(defenseId);
        runtime.rebuild(defenseId);
        assertEquals(0, runtime.effects().size());
    }

    @Test
    void preservesExplicitConstructorNullGuards() {
        assertThrows(
                NullPointerException.class,
                () -> new TacticalBuildRuntime(null, (TacticalEffectCache) null));
        assertThrows(
                NullPointerException.class,
                () -> new TacticalBuildRuntime(null, (TacticalBuildStateProvider) null));
    }
}
