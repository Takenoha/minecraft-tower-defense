package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.persistence.TeamResourceSnapshot;
import io.github.takenoha.towerdefense.persistence.TowerRecord;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Optional;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin tower-management GUI builder. */
class TowerManagementGuiKotlinBoundaryAbiTest {
    @Test
    void guiBoundaryRemainsCompatible() throws Exception {
        Class<?> type = TowerManagementGui.class;
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));

        Constructor<?> constructor = type.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));

        assertConstant(type, "SIZE", 27);
        assertConstant(type, "PRIORITY_START_SLOT", 9);
        assertConstant(type, "BOOST_POWER_SLOT", 1);
        assertConstant(type, "BOOST_SPEED_SLOT", 2);
        assertConstant(type, "BOOST_RANGE_SLOT", 3);
        assertConstant(type, "REPAIR_SLOT", 5);
        assertConstant(type, "UPGRADE_SLOT", 18);
        assertConstant(type, "LEGACY_UPGRADE_SLOT", 19);
        assertConstant(type, "REMOVE_SLOT", 20);
        assertConstant(type, "HELP_SLOT", 22);
        assertConstant(type, "CLOSE_SLOT", 26);

        assertStatic(
                type,
                "create",
                Inventory.class,
                TowerRecord.class,
                boolean.class,
                String.class);
        assertStatic(
                type,
                "create",
                Inventory.class,
                TowerRecord.class,
                boolean.class,
                String.class,
                int.class,
                int.class,
                int.class);
        assertStatic(
                type,
                "create",
                Inventory.class,
                TowerRecord.class,
                boolean.class,
                String.class,
                int.class,
                int.class,
                int.class,
                boolean.class,
                long.class,
                Map.class,
                int.class,
                int.class,
                int.class,
                boolean.class,
                long.class,
                long.class,
                int.class);
        assertStatic(
                type,
                "create",
                Inventory.class,
                TowerRecord.class,
                boolean.class,
                String.class,
                int.class,
                int.class,
                int.class,
                boolean.class,
                long.class,
                Map.class,
                int.class,
                int.class,
                int.class,
                boolean.class,
                long.class,
                long.class,
                int.class,
                TeamResourceSnapshot.class);
        assertStatic(
                type,
                "create",
                Inventory.class,
                TowerRecord.class,
                boolean.class,
                String.class,
                int.class,
                int.class,
                int.class,
                boolean.class,
                long.class,
                Map.class,
                int.class,
                int.class,
                int.class,
                boolean.class,
                long.class,
                long.class,
                int.class,
                TeamResourceSnapshot.class,
                boolean.class);
        assertStatic(type, "priorityAt", Optional.class, int.class);
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
