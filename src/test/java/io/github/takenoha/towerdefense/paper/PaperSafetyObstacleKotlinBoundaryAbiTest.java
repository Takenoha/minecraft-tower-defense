package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.config.ProtectionSettings;
import io.github.takenoha.towerdefense.domain.CombatArea;
import io.github.takenoha.towerdefense.domain.EnemyObstacleFacts;
import io.github.takenoha.towerdefense.runtime.CoreRegistry;
import io.github.takenoha.towerdefense.runtime.EnemyAccessPolicy;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.List;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin Paper safety and obstacle boundaries. */
class PaperSafetyObstacleKotlinBoundaryAbiTest {
    @Test
    void adapterAndUtilityBoundariesRemainCompatible() throws Exception {
        assertTrue(ThirdPartyRegionProtectionAdapter.class.isInterface());
        assertMethod(
                ThirdPartyRegionProtectionAdapter.class,
                "violations",
                List.class,
                World.class,
                double.class,
                double.class,
                double.class);
        assertStaticMethod(
                ThirdPartyRegionProtectionAdapter.class,
                "none",
                ThirdPartyRegionProtectionAdapter.class);
        assertStaticMethod(
                ThirdPartyRegionProtectionAdapter.class,
                "unavailable",
                ThirdPartyRegionProtectionAdapter.class,
                String.class);

        assertPrivateUtility(PaperCombatAreaSafetyValidator.class);
        assertStaticMethod(
                PaperCombatAreaSafetyValidator.class,
                "violations",
                List.class,
                World.class,
                double.class,
                double.class,
                CombatArea.class,
                ProtectionSettings.class);
        assertStaticMethod(
                PaperCombatAreaSafetyValidator.class,
                "violations",
                List.class,
                World.class,
                double.class,
                double.class,
                CombatArea.class,
                ProtectionSettings.class,
                ThirdPartyRegionProtectionAdapter.class);

        assertPrivateUtility(PaperEnemyObstacleClassifier.class);
        assertStaticMethod(
                PaperEnemyObstacleClassifier.class,
                "classify",
                EnemyObstacleFacts.class,
                Block.class,
                BlockData.class,
                CoreRegistry.class,
                EnemyAccessPolicy.class);
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

    private static void assertMethod(
            Class<?> type,
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) throws Exception {
        var method = type.getMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
        assertTrue(Modifier.isAbstract(method.getModifiers()), name);
    }
}
