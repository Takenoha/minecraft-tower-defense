package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.EnemyRole;
import io.github.takenoha.towerdefense.runtime.CoreRegistry;
import io.github.takenoha.towerdefense.runtime.EnemyAccessPolicy;
import io.github.takenoha.towerdefense.runtime.TerrainMutationPolicy;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin Paper terrain-action boundary. */
class PaperEnemyTerrainActionKotlinBoundaryAbiTest {
    @Test
    void constructorAndActionMethodsRemainCompatible() throws Exception {
        assertTrue(Modifier.isPublic(PaperEnemyTerrainAction.class.getModifiers()));
        assertTrue(Modifier.isFinal(PaperEnemyTerrainAction.class.getModifiers()));
        Constructor<?> constructor = PaperEnemyTerrainAction.class.getConstructor(
                TerrainMutationPolicy.class,
                PaperBlockMutationAdapter.class,
                PaperEscrowDropManager.class,
                CoreRegistry.class,
                EnemyAccessPolicy.class);
        assertTrue(Modifier.isPublic(constructor.getModifiers()));
        assertMethod(
                "tryApply",
                boolean.class,
                EntityChangeBlockEvent.class,
                io.github.takenoha.towerdefense.runtime.TaggedEnemy.class);
        assertMethod(
                "tryBreakObstacle",
                boolean.class,
                Entity.class,
                Location.class,
                io.github.takenoha.towerdefense.runtime.TaggedEnemy.class);
        assertMethod(
                "tryBuildBridge",
                boolean.class,
                Entity.class,
                Location.class,
                io.github.takenoha.towerdefense.runtime.TaggedEnemy.class);
    }

    private static void assertMethod(
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) throws Exception {
        var method = PaperEnemyTerrainAction.class.getMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
        assertTrue(Modifier.isFinal(method.getModifiers()), name);
    }
}
