package io.github.takenoha.towerdefense.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.config.CombatSettings;
import io.github.takenoha.towerdefense.config.ForbiddenRegion;
import io.github.takenoha.towerdefense.config.TerrainMutationSettings;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigRecordsKotlinBoundaryAbiTest {
    @Test
    void preservesRecordComponentsAndCanonicalConstructors() throws Exception {
        assertRecord(CombatSettings.class,
                new Class<?>[]{double.class, double.class, double.class, double.class, double.class,
                        int.class, int.class, int.class, int.class, int.class},
                "radius", "spawnInner", "spawnOuter", "minimumCoreDistance", "coreGap",
                "maxParticipants", "countdownSeconds", "preparationSeconds",
                "intermissionSeconds", "absenceGraceSeconds");
        assertRecord(ForbiddenRegion.class,
                new Class<?>[]{String.class, double.class, double.class, double.class, double.class},
                "worldName", "minX", "minZ", "maxX", "maxZ");
        assertRecord(TerrainMutationSettings.class,
                new Class<?>[]{boolean.class, boolean.class, boolean.class},
                "requested", "paperIntegrationVerified", "recoveryVerified");
    }

    @Test
    void preservesMethodsAndFactories() throws Exception {
        assertPublicStatic(TerrainMutationSettings.class, "disabled");
        assertPublicInstance(ForbiddenRegion.class, "contains",
                String.class, double.class, double.class);
        assertPublicInstance(ForbiddenRegion.class, "intersectsCircle",
                String.class, double.class, double.class, double.class);
    }

    private static void assertPublicStatic(Class<?> type, String name, Class<?>... parameters)
            throws Exception {
        var method = type.getMethod(name, parameters);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
        assertTrue(Modifier.isStatic(method.getModifiers()), name);
    }

    private static void assertPublicInstance(Class<?> type, String name, Class<?>... parameters)
            throws Exception {
        var method = type.getMethod(name, parameters);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
        assertTrue(!Modifier.isStatic(method.getModifiers()), name);
    }

    private static void assertRecord(Class<?> type, Class<?>[] componentTypes, String... names)
            throws Exception {
        assertTrue(type.isRecord(), type.getName());
        assertTrue(Modifier.isPublic(type.getModifiers()), type.getName());
        assertTrue(Modifier.isFinal(type.getModifiers()), type.getName());
        assertEquals(List.of(names), Arrays.stream(type.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList());
        assertEquals(names.length, componentTypes.length);
        assertNotNull(type.getConstructor(componentTypes));
    }
}
