package io.github.takenoha.towerdefense.tactical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TacticalInterfacesKotlinBoundaryAbiTest {
    @Test
    void preservesLifecycleAndStateProviderDescriptors() throws Exception {
        assertTrue(TacticalBuildLifecycle.class.isInterface());
        var activate = TacticalBuildLifecycle.class.getMethod(
                "activateAtPreparation", UUID.class, UUID.class);
        assertEquals(TacticalUnlockResult.class, activate.getReturnType());
        assertTrue(Modifier.isPublic(activate.getModifiers()));
        var advance = TacticalBuildLifecycle.class.getMethod(
                "advanceAfterWave", UUID.class, int.class, int.class, UUID.class);
        assertEquals(TacticalUnlockResult.class, advance.getReturnType());
        assertEquals(void.class, TacticalBuildLifecycle.class.getMethod(
                "markTerminal", UUID.class, TacticalTerminalResult.class, UUID.class)
                .getReturnType());

        assertTrue(TacticalBuildStateProvider.class.isInterface());
        assertEquals(Optional.class, TacticalBuildStateProvider.class.getMethod(
                "findActiveByDefense", UUID.class).getReturnType());
    }

    @Test
    void preservesSnapshotProviderAndHotPathDescriptors() throws Exception {
        assertTrue(TacticalEffectSnapshotProvider.class.isInterface());
        assertEquals(TacticalEffectSnapshot.class, TacticalEffectSnapshotProvider.class.getMethod(
                "currentForDefense", UUID.class).getReturnType());
        assertTrue(TacticalEffectSnapshot.class.isInterface());
        assertEquals(double.class, TacticalEffectSnapshot.class.getMethod(
                "damageMultiplier", io.github.takenoha.towerdefense.domain.TowerType.class,
                TacticalTargetContext.class).getReturnType());
        assertEquals(double.class, TacticalEffectSnapshot.class.getMethod(
                "attackIntervalMultiplier", io.github.takenoha.towerdefense.domain.TowerType.class,
                TacticalTargetContext.class).getReturnType());
        assertEquals(double.class, TacticalEffectSnapshot.class.getMethod(
                "rangeAdd", io.github.takenoha.towerdefense.domain.TowerType.class)
                .getReturnType());
        assertEquals(double.class, TacticalEffectSnapshot.class.getMethod(
                "areaRadiusMultiplier", io.github.takenoha.towerdefense.domain.TowerType.class)
                .getReturnType());
        assertEquals(int.class, TacticalEffectSnapshot.class.getMethod(
                "chainCountAdd", io.github.takenoha.towerdefense.domain.TowerType.class)
                .getReturnType());
        assertEquals(double.class, TacticalEffectSnapshot.class.getMethod(
                "slowStrengthMultiplier", io.github.takenoha.towerdefense.domain.TowerType.class)
                .getReturnType());
        assertEquals(double.class, TacticalEffectSnapshot.class.getMethod(
                "burnDurationMultiplier", io.github.takenoha.towerdefense.domain.TowerType.class)
                .getReturnType());
        assertEquals(double.class, TacticalEffectSnapshot.class.getMethod(
                "supportBuffMultiplier").getReturnType());
        assertEquals(double.class, TacticalEffectSnapshot.class.getMethod(
                "repairCostMultiplier").getReturnType());
        assertEquals(double.class, TacticalEffectSnapshot.class.getMethod(
                "towerDamageTakenMultiplier").getReturnType());
    }

    @Test
    void preservesTerminalEnumOrder() {
        assertEquals(
                List.of("VICTORY", "DEFEAT", "ABORTED", "RECOVERY"),
                java.util.Arrays.stream(TacticalTerminalResult.values())
                        .map(Enum::name)
                        .toList());
    }
}
