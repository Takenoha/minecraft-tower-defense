package io.github.takenoha.towerdefense.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.config.TowerProfile;
import io.github.takenoha.towerdefense.domain.CombatArea;
import io.github.takenoha.towerdefense.domain.CoreState;
import io.github.takenoha.towerdefense.domain.WorldBorderSnapshot;
import io.github.takenoha.towerdefense.runtime.EnemyBridgePlan;
import io.github.takenoha.towerdefense.runtime.TerrainMutationInput;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class DomainRecordsKotlinBoundaryAbiTest {
    @Test
    void preservesRecordComponentsAndCanonicalConstructors() throws Exception {
        assertRecord(CombatArea.class,
                new Class<?>[]{double.class, double.class, double.class, double.class, double.class},
                "radius", "spawnInner", "spawnOuter", "minimumCoreDistance", "coreGap");
        assertRecord(CoreState.class,
                new Class<?>[]{long.class, long.class, boolean.class},
                "maximumHitPoints", "currentHitPoints", "present");
        assertRecord(WorldBorderSnapshot.class,
                new Class<?>[]{double.class, double.class, double.class},
                "centerX", "centerZ", "size");
        assertRecord(EnemyBridgePlan.class,
                new Class<?>[]{String.class}, "targetMaterialKey");
        assertRecord(TerrainMutationInput.class,
                new Class<?>[]{String.class, boolean.class, boolean.class, boolean.class, String.class},
                "currentMaterialKey", "currentInventoryHolder", "currentCore",
                "currentTileState", "targetMaterialKey");
        assertRecord(TowerProfile.class,
                new Class<?>[]{int.class, double.class, int.class, double.class, double.class,
                        int.class, int.class, double.class, double.class, double.class,
                        double.class, double.class, int.class, int.class},
                "damage", "range", "attackIntervalTicks", "areaRadius", "slowPercent",
                "slowDurationTicks", "chainCount", "chainRadius", "supportRadius",
                "supportDamageMultiplier", "supportSpeedMultiplier", "supportRangeMultiplier",
                "supportStackLimit", "burnDurationTicks");
    }

    @Test
    void preservesMethodsFactoriesAndCompatibilityConstructor() throws Exception {
        assertPublicStatic(CombatArea.class, "horizontalDistance",
                double.class, double.class, double.class, double.class);
        assertPublicInstance(CombatArea.class, "requiredCoreDistance");
        assertPublicInstance(CombatArea.class, "contains",
                double.class, double.class, double.class, double.class);
        assertPublicInstance(CombatArea.class, "isInSpawnBand",
                double.class, double.class, double.class, double.class);
        assertPublicInstance(CombatArea.class, "coresAreFarEnoughApart",
                double.class, double.class, double.class, double.class);

        assertPublicStatic(CoreState.class, "intact", long.class);
        assertPublicStatic(CoreState.class, "destroyed", long.class);
        assertPublicInstance(CoreState.class, "isDestroyed");
        assertPublicInstance(CoreState.class, "damage", long.class);
        assertPublicInstance(CoreState.class, "repair", long.class);
        assertPublicInstance(WorldBorderSnapshot.class, "containsCircle",
                double.class, double.class, double.class);

        assertNotNull(TerrainMutationInput.class.getConstructor(
                String.class, boolean.class, boolean.class, String.class));
        for (String factory : List.of(
                "frostDefaults", "lightningDefaults", "supportDefaults",
                "sniperDefaults", "flameDefaults")) {
            assertPublicStatic(TowerProfile.class, factory);
        }
    }

    private static void assertPublicStatic(Class<?> type, String name, Class<?>... parameters)
            throws Exception {
        var method = type.getMethod(name, parameters);
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertTrue(Modifier.isStatic(method.getModifiers()));
    }

    private static void assertPublicInstance(Class<?> type, String name, Class<?>... parameters)
            throws Exception {
        var method = type.getMethod(name, parameters);
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertTrue(!Modifier.isStatic(method.getModifiers()));
    }

    private static void assertRecord(Class<?> type, Class<?>[] componentTypes, String... names)
            throws Exception {
        assertTrue(type.isRecord(), type.getName());
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertEquals(List.of(names), Arrays.stream(type.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList());
        assertEquals(names.length, componentTypes.length);
        assertNotNull(type.getConstructor(componentTypes));
    }
}
