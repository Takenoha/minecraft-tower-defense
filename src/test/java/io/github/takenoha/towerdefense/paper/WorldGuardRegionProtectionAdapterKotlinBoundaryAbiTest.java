package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin WorldGuard soft-dependency adapter. */
class WorldGuardRegionProtectionAdapterKotlinBoundaryAbiTest {
    @Test
    void adapterBoundaryRemainsCompatible() throws Exception {
        Class<?> type = WorldGuardRegionProtectionAdapter.class;
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        Constructor<?> constructor = type.getDeclaredConstructor(JavaPlugin.class);
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));

        Method discover = type.getMethod("discover", JavaPlugin.class);
        assertEquals(ThirdPartyRegionProtectionAdapter.class, discover.getReturnType());
        assertTrue(Modifier.isPublic(discover.getModifiers()));
        assertTrue(Modifier.isStatic(discover.getModifiers()));

        Method violations = type.getMethod(
                "violations", World.class, double.class, double.class, double.class);
        assertEquals(List.class, violations.getReturnType());
        assertTrue(Modifier.isPublic(violations.getModifiers()));
    }
}
