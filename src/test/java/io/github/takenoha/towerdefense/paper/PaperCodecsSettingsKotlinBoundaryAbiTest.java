package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.config.PluginSettings;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin Paper codec and settings utility boundaries. */
class PaperCodecsSettingsKotlinBoundaryAbiTest {
    @Test
    void utilityConstructorsAndStaticMethodsRemainCompatible() throws Exception {
        assertPrivateUtility(PaperItemStackCodec.class);
        assertStaticMethod(PaperItemStackCodec.class, "encode", String.class, ItemStack.class);
        assertStaticMethod(PaperItemStackCodec.class, "decode", ItemStack.class, String.class);

        assertPrivateUtility(PaperSettingsLoader.class);
        assertStaticMethod(PaperSettingsLoader.class, "load", PluginSettings.class, FileConfiguration.class);
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
}
