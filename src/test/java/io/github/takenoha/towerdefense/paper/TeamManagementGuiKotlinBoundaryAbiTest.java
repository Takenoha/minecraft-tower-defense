package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.persistence.CoreRecord;
import io.github.takenoha.towerdefense.persistence.TeamRecord;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin team-management GUI builder. */
class TeamManagementGuiKotlinBoundaryAbiTest {
    @Test
    void guiBoundaryRemainsCompatible() throws Exception {
        Class<?> type = TeamManagementGui.class;
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));

        Constructor<?> constructor = type.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));

        assertConstant(type, "SIZE", 54);
        assertConstant(type, "INVITE_SLOT", 45);
        assertConstant(type, "LEAVE_SLOT", 47);
        assertConstant(type, "RENAME_SLOT", 51);
        assertConstant(type, "CLOSE_SLOT", 53);
        assertConstant(type, "CONFIRM_SLOT", 11);
        assertConstant(type, "CANCEL_SLOT", 15);

        assertStatic(type, "create", Inventory.class, CoreRecord.class, TeamRecord.class, UUID.class);
        assertStatic(
                type,
                "createConfirmation",
                Inventory.class,
                UUID.class,
                UUID.class,
                TeamManagementConfirmationHolder.Action.class);
    }

    private static void assertStatic(
            Class<?> type,
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) throws Exception {
        Method method = type.getMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
        assertTrue(Modifier.isStatic(method.getModifiers()), name);
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
