package io.github.takenoha.towerdefense.tactical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.TowerType;
import java.lang.reflect.Modifier;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TacticalEffectSnapshotImplKotlinBoundaryAbiTest {
    @Test
    void keepsSnapshotImplementationBoundaryAndMethods() throws Exception {
        assertTrue(Modifier.isPublic(TacticalEffectSnapshotImpl.class.getModifiers()));
        assertTrue(Modifier.isFinal(TacticalEffectSnapshotImpl.class.getModifiers()));
        assertTrue(TacticalEffectSnapshot.class.isAssignableFrom(TacticalEffectSnapshotImpl.class));

        var constructor = TacticalEffectSnapshotImpl.class.getDeclaredConstructor(
                EnumMap.class,
                EnumMap.class,
                EnumMap.class,
                EnumMap.class,
                EnumMap.class,
                EnumMap.class,
                EnumMap.class,
                double.class,
                double.class,
                double.class);
        assertTrue(Modifier.isPublic(constructor.getModifiers()));

        for (var method : new String[] {
                "damageMultiplier",
                "attackIntervalMultiplier",
        }) {
            var reflected = TacticalEffectSnapshotImpl.class.getMethod(
                    method, TowerType.class, TacticalTargetContext.class);
            assertTrue(Modifier.isPublic(reflected.getModifiers()));
            assertEquals(double.class, reflected.getReturnType());
        }
        for (var method : new String[] {
                "rangeAdd",
                "areaRadiusMultiplier",
                "chainCountAdd",
                "slowStrengthMultiplier",
                "burnDurationMultiplier",
        }) {
            var reflected = TacticalEffectSnapshotImpl.class.getMethod(method, TowerType.class);
            assertTrue(Modifier.isPublic(reflected.getModifiers()));
        }
        assertEquals(double.class, TacticalEffectSnapshotImpl.class
                .getMethod("supportBuffMultiplier").getReturnType());
        assertEquals(double.class, TacticalEffectSnapshotImpl.class
                .getMethod("repairCostMultiplier").getReturnType());
        assertEquals(double.class, TacticalEffectSnapshotImpl.class
                .getMethod("towerDamageTakenMultiplier").getReturnType());

        var safeProduct = TacticalEffectSnapshotImpl.class.getMethod(
                "safeProduct", double.class, double.class);
        assertTrue(Modifier.isPublic(safeProduct.getModifiers()));
        assertTrue(Modifier.isStatic(safeProduct.getModifiers()));
        assertEquals(double.class, safeProduct.getReturnType());
    }

    @Test
    void preservesDeepCopiesConditionalMatchingAndNeutralGuards() {
        var damage = conditionalMap();
        damage.get(TowerType.ARROW).put(TacticalTargetCondition.BOSS, 2.0);
        var attackInterval = conditionalMap();
        var rangeAdd = new EnumMap<TowerType, Double>(TowerType.class);
        rangeAdd.put(TowerType.ARROW, 1.5);
        var areaRadius = new EnumMap<TowerType, Double>(TowerType.class);
        var chainCount = new EnumMap<TowerType, Integer>(TowerType.class);
        var slowStrength = new EnumMap<TowerType, Double>(TowerType.class);
        var burnDuration = new EnumMap<TowerType, Double>(TowerType.class);

        var snapshot = new TacticalEffectSnapshotImpl(
                damage,
                attackInterval,
                rangeAdd,
                areaRadius,
                chainCount,
                slowStrength,
                burnDuration,
                0.0,
                Double.NaN,
                Double.POSITIVE_INFINITY);
        damage.get(TowerType.ARROW).put(TacticalTargetCondition.BOSS, 9.0);

        assertEquals(2.0, snapshot.damageMultiplier(
                TowerType.ARROW,
                new TacticalTargetContext(1.0, 1.0, true, false, false)));
        assertEquals(1.0, snapshot.damageMultiplier(
                TowerType.ARROW,
                new TacticalTargetContext(1.0, 1.0, false, false, false)));
        assertEquals(1.5, snapshot.rangeAdd(TowerType.ARROW));
        assertEquals(1.0, snapshot.supportBuffMultiplier());
        assertEquals(1.0, snapshot.repairCostMultiplier());
        assertEquals(1.0, snapshot.towerDamageTakenMultiplier());
        assertEquals(1.0, TacticalEffectSnapshotImpl.safeProduct(0.0, 2.0));
        assertEquals(1.0, TacticalEffectSnapshotImpl.safeProduct(Double.MAX_VALUE, 2.0));
    }

    private static EnumMap<TowerType, EnumMap<TacticalTargetCondition, Double>> conditionalMap() {
        var map = new EnumMap<TowerType, EnumMap<TacticalTargetCondition, Double>>(TowerType.class);
        for (var type : TowerType.values()) {
            map.put(type, new EnumMap<>(TacticalTargetCondition.class));
        }
        return map;
    }
}
