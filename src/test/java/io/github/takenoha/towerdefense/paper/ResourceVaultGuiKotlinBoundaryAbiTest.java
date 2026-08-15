package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.persistence.TeamResourceSnapshot;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin resource vault GUI builder. */
class ResourceVaultGuiKotlinBoundaryAbiTest {
    @Test
    void guiBoundaryRemainsCompatible() throws Exception {
        Class<?> type = ResourceVaultGui.class;
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));

        Constructor<?> constructor = type.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));

        assertConstant(type, "SIZE", 27);
        assertConstant(type, "DEFENSE_SLOT", 11);
        assertConstant(type, "ENHANCEMENT_SLOT", 15);
        assertConstant(type, "DEFENSE_TEN_SLOT", 10);
        assertConstant(type, "DEFENSE_HUNDRED_SLOT", 12);
        assertConstant(type, "DEFENSE_ALL_SLOT", 13);
        assertConstant(type, "ENHANCEMENT_ONE_SLOT", 14);
        assertConstant(type, "ENHANCEMENT_TEN_SLOT", 16);
        assertConstant(type, "ENHANCEMENT_ALL_SLOT", 17);
        assertConstant(type, "CLOSE_SLOT", 22);

        assertCreate(type, UUID.class, TeamResourceSnapshot.class);
        assertCreate(type, UUID.class, TeamResourceSnapshot.class, boolean.class, boolean.class);
    }

    private static void assertCreate(Class<?> type, Class<?>... parameterTypes) throws Exception {
        Method method = type.getMethod("create", parameterTypes);
        assertEquals(Inventory.class, method.getReturnType());
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertTrue(Modifier.isStatic(method.getModifiers()));
    }

    private static void assertConstant(Class<?> type, String name, int expected) throws Exception {
        Field field = type.getField(name);
        assertEquals(int.class, field.getType(), name);
        assertTrue(Modifier.isPublic(field.getModifiers()), name);
        assertTrue(Modifier.isStatic(field.getModifiers()), name);
        assertTrue(Modifier.isFinal(field.getModifiers()), name);
        assertEquals(expected, field.getInt(null), name);
    }
}
