package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.EnemyObstacleFacts;
import io.github.takenoha.towerdefense.domain.EnemyRole;
import io.github.takenoha.towerdefense.persistence.BlockStateSnapshot;
import io.github.takenoha.towerdefense.runtime.CoreRegistry;
import io.github.takenoha.towerdefense.runtime.EnemyAccessPolicy;
import io.github.takenoha.towerdefense.runtime.EnemyBridgePlan;
import io.github.takenoha.towerdefense.runtime.EnemyPathMetrics;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin Paper path boundaries. */
class PaperEnemyPathKotlinBoundaryAbiTest {
    @Test
    void controllerRecordsAndIntegrationBoundaryRemainCompatible() throws Exception {
        assertPrivateUtility(PaperEnemyPathController.class);
        assertStaticMethod(
                PaperEnemyPathController.class,
                "inspect",
                EnemyObstacleFacts.class,
                Entity.class,
                Location.class,
                EnemyRole.class,
                CoreRegistry.class,
                EnemyAccessPolicy.class);
        assertStaticMethod(
                PaperEnemyPathController.class,
                "planBreak",
                Optional.class,
                Entity.class,
                Location.class,
                EnemyRole.class,
                CoreRegistry.class,
                EnemyAccessPolicy.class);
        assertStaticMethod(
                PaperEnemyPathController.class,
                "planBridge",
                Optional.class,
                Entity.class,
                Location.class,
                EnemyRole.class,
                CoreRegistry.class,
                EnemyAccessPolicy.class,
                long.class);
        assertRecord(
                PaperEnemyPathController.BreakCandidate.class,
                new String[] {"block", "targetBlockData", "facts", "observedBefore"},
                new Class<?>[] {Block.class, String.class, EnemyObstacleFacts.class, BlockStateSnapshot.class});
        assertRecord(
                PaperEnemyPathController.BridgeCandidate.class,
                new String[] {"block", "targetBlockData", "plan", "facts", "observedBefore"},
                new Class<?>[] {
                    Block.class,
                    String.class,
                    EnemyBridgePlan.class,
                    EnemyObstacleFacts.class,
                    BlockStateSnapshot.class
                });

        assertTrue(Modifier.isPublic(PaperEnemyPathIntegrationBoundary.class.getModifiers()));
        assertTrue(Modifier.isFinal(PaperEnemyPathIntegrationBoundary.class.getModifiers()));
        var constructor = PaperEnemyPathIntegrationBoundary.class.getConstructor(
                CoreRegistry.class,
                EnemyAccessPolicy.class);
        assertTrue(Modifier.isPublic(constructor.getModifiers()));
        var inspect = PaperEnemyPathIntegrationBoundary.class.getMethod(
                "inspect",
                Entity.class,
                Location.class,
                EnemyRole.class,
                EnemyPathMetrics.class);
        assertEquals(EnemyObstacleFacts.class, inspect.getReturnType());
        assertTrue(Modifier.isPublic(inspect.getModifiers()));
    }

    private static void assertPrivateUtility(Class<?> type) throws Exception {
        assertTrue(Modifier.isPublic(type.getModifiers()), type.getName());
        assertTrue(Modifier.isFinal(type.getModifiers()), type.getName());
        Constructor<?> constructor = type.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(constructor.getModifiers()), type.getName());
    }

    private static void assertStaticMethod(
            Class<?> type,
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) throws Exception {
        var method = type.getMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
        assertTrue(Modifier.isStatic(method.getModifiers()), name);
    }

    private static void assertRecord(
            Class<?> type,
            String[] componentNames,
            Class<?>[] componentTypes) throws Exception {
        assertTrue(type.isRecord(), type.getName());
        var components = type.getRecordComponents();
        assertEquals(componentNames.length, components.length, type.getName());
        for (int index = 0; index < components.length; index++) {
            assertEquals(componentNames[index], components[index].getName());
            assertEquals(componentTypes[index], components[index].getType());
            assertEquals(componentTypes[index], type.getMethod(componentNames[index]).getReturnType());
        }
        var constructor = type.getConstructor(componentTypes);
        assertTrue(Modifier.isPublic(constructor.getModifiers()), type.getName());
    }
}
