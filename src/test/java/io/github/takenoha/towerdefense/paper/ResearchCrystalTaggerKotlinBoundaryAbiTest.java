package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin research crystal tagger. */
class ResearchCrystalTaggerKotlinBoundaryAbiTest {
    @Test
    void constantsConstructorsAndMethodsRemainJavaCompatible() throws Exception {
        Class<?> type = ResearchCrystalTagger.class;
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertField(type, "ITEM_VERSION", int.class, 1);
        assertField(type, "STACK_LIMIT", int.class, 64);

        assertPublicConstructor(type, Plugin.class);
        assertPublicConstructor(type, String.class);
        assertMethod(type, "create", ItemStack.class, UUID.class, UUID.class, int.class);
        assertMethod(
                type,
                "create",
                ItemStack.class,
                UUID.class,
                UUID.class,
                int.class,
                Integer.class,
                Integer.class);
        assertMethod(type, "read", Optional.class, ItemStack.class);
        assertMethod(type, "readWithRedemptionReceipt", Optional.class, ItemStack.class);
        assertMethod(type, "tagRedemption", void.class, ItemStack.class, UUID.class);
        assertMethod(type, "hasRedemptionReceipt", boolean.class, ItemStack.class);
        assertMethod(type, "redemptionOperationId", Optional.class, ItemStack.class);
        assertMethod(type, "clearRedemptionReceipt", void.class, ItemStack.class);
    }

    private static void assertField(Class<?> type, String name, Class<?> fieldType, int value)
            throws Exception {
        Field field = type.getField(name);
        assertEquals(fieldType, field.getType());
        assertTrue(Modifier.isPublic(field.getModifiers()));
        assertTrue(Modifier.isStatic(field.getModifiers()));
        assertTrue(Modifier.isFinal(field.getModifiers()));
        assertEquals(value, field.getInt(null));
    }

    private static void assertPublicConstructor(Class<?> type, Class<?>... parameterTypes)
            throws Exception {
        Constructor<?> constructor = type.getConstructor(parameterTypes);
        assertTrue(Modifier.isPublic(constructor.getModifiers()));
    }

    private static void assertMethod(
            Class<?> type,
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) throws Exception {
        Method method = type.getMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
    }
}
