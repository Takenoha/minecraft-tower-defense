package io.github.takenoha.towerdefense.tactical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.TowerType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class EmptyTacticalEffectSnapshotKotlinBoundaryAbiTest {
    @Test
    void preservesSingletonClassFieldAndPrivateConstructor() throws Exception {
        assertTrue(Modifier.isPublic(EmptyTacticalEffectSnapshot.class.getModifiers()));
        assertTrue(Modifier.isFinal(EmptyTacticalEffectSnapshot.class.getModifiers()));
        assertTrue(TacticalEffectSnapshot.class.isAssignableFrom(EmptyTacticalEffectSnapshot.class));

        Field instance = EmptyTacticalEffectSnapshot.class.getField("INSTANCE");
        assertTrue(Modifier.isPublic(instance.getModifiers()));
        assertTrue(Modifier.isStatic(instance.getModifiers()));
        assertTrue(Modifier.isFinal(instance.getModifiers()));
        assertEquals(EmptyTacticalEffectSnapshot.class, instance.getType());
        assertSame(EmptyTacticalEffectSnapshot.INSTANCE, instance.get(null));

        Constructor<EmptyTacticalEffectSnapshot> constructor =
                EmptyTacticalEffectSnapshot.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));
    }

    @Test
    void preservesNeutralSnapshotMethods() throws Exception {
        assertEquals(double.class, EmptyTacticalEffectSnapshot.class.getMethod(
                "damageMultiplier", TowerType.class, TacticalTargetContext.class).getReturnType());
        assertEquals(double.class, EmptyTacticalEffectSnapshot.class.getMethod(
                "attackIntervalMultiplier", TowerType.class, TacticalTargetContext.class)
                .getReturnType());
        assertEquals(double.class, EmptyTacticalEffectSnapshot.class.getMethod(
                "rangeAdd", TowerType.class).getReturnType());
        assertEquals(double.class, EmptyTacticalEffectSnapshot.class.getMethod(
                "areaRadiusMultiplier", TowerType.class).getReturnType());
        assertEquals(int.class, EmptyTacticalEffectSnapshot.class.getMethod(
                "chainCountAdd", TowerType.class).getReturnType());
        assertEquals(double.class, EmptyTacticalEffectSnapshot.class.getMethod(
                "slowStrengthMultiplier", TowerType.class).getReturnType());
        assertEquals(double.class, EmptyTacticalEffectSnapshot.class.getMethod(
                "burnDurationMultiplier", TowerType.class).getReturnType());
        assertEquals(double.class, EmptyTacticalEffectSnapshot.class.getMethod(
                "supportBuffMultiplier").getReturnType());
        assertEquals(double.class, EmptyTacticalEffectSnapshot.class.getMethod(
                "repairCostMultiplier").getReturnType());
        assertEquals(double.class, EmptyTacticalEffectSnapshot.class.getMethod(
                "towerDamageTakenMultiplier").getReturnType());
    }

    @Test
    void preservesNeutralValues() {
        EmptyTacticalEffectSnapshot snapshot = EmptyTacticalEffectSnapshot.INSTANCE;
        var target = TacticalTargetContext.neutral();
        assertEquals(1.0, snapshot.damageMultiplier(TowerType.ARROW, target));
        assertEquals(1.0, snapshot.attackIntervalMultiplier(TowerType.ARROW, target));
        assertEquals(0.0, snapshot.rangeAdd(TowerType.ARROW));
        assertEquals(1.0, snapshot.areaRadiusMultiplier(TowerType.ARROW));
        assertEquals(0, snapshot.chainCountAdd(TowerType.ARROW));
        assertEquals(1.0, snapshot.slowStrengthMultiplier(TowerType.ARROW));
        assertEquals(1.0, snapshot.burnDurationMultiplier(TowerType.ARROW));
        assertEquals(1.0, snapshot.supportBuffMultiplier());
        assertEquals(1.0, snapshot.repairCostMultiplier());
        assertEquals(1.0, snapshot.towerDamageTakenMultiplier());
        assertNotNull(snapshot);
    }
}
