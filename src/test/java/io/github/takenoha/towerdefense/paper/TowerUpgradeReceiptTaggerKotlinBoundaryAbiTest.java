package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin tower-upgrade receipt tagger. */
class TowerUpgradeReceiptTaggerKotlinBoundaryAbiTest {
    @Test
    void constructorAndReceiptMethodsRemainCompatible() throws Exception {
        Class<?> type = TowerUpgradeReceiptTagger.class;
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        Constructor<?> constructor = type.getConstructor(Plugin.class);
        assertTrue(Modifier.isPublic(constructor.getModifiers()));

        assertMethod("tag", ItemStack.class, ItemStack.class, UUID.class, String.class);
        assertMethod("strip", ItemStack.class, ItemStack.class);
        assertMethod("operationId", Optional.class, ItemStack.class);
        assertMethod("material", Optional.class, ItemStack.class);
        assertMethod("isTagged", boolean.class, ItemStack.class);
        assertMethod("isFor", boolean.class, ItemStack.class, UUID.class, String.class);
    }

    private static void assertMethod(
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) throws Exception {
        Method method = TowerUpgradeReceiptTagger.class.getMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
        assertTrue(Modifier.isFinal(method.getModifiers()), name);
    }
}
