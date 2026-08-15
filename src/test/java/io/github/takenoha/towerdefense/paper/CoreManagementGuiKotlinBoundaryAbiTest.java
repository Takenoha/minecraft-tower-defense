package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.CoreRepairCost;
import io.github.takenoha.towerdefense.domain.TeamProgress;
import io.github.takenoha.towerdefense.persistence.CoreRecord;
import io.github.takenoha.towerdefense.persistence.TeamRecord;
import io.github.takenoha.towerdefense.persistence.TeamResourceSnapshot;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.OptionalLong;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin core-management GUI builder. */
class CoreManagementGuiKotlinBoundaryAbiTest {
    @Test
    void guiBoundaryRemainsCompatible() throws Exception {
        Class<?> type = CoreManagementGui.class;
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));

        Constructor<?> constructor = type.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));

        assertConstant(type, "SIZE", 27);
        assertConstant(type, "TEAM_SLOT", 0);
        assertConstant(type, "RESOURCE_VAULT_SLOT", 1);
        assertConstant(type, "RESEARCH_DEPOSIT_SLOT", 9);
        assertConstant(type, "TOWER_RESEARCH_SLOT", 10);
        assertConstant(type, "REPAIR_SLOT", 11);
        assertConstant(type, "LEGACY_REPAIR_SLOT", 12);
        assertConstant(type, "START_SLOT", 13);
        assertConstant(type, "RELOCATE_SLOT", 15);
        assertConstant(type, "CLOSE_SLOT", 22);

        assertStatic(
                type,
                "create",
                Inventory.class,
                CoreRecord.class,
                TeamRecord.class,
                TeamProgress.class,
                CoreRepairCost.class,
                String.class);
        assertStatic(
                type,
                "create",
                Inventory.class,
                CoreRecord.class,
                TeamRecord.class,
                TeamProgress.class,
                CoreRepairCost.class,
                String.class,
                TeamResourceSnapshot.class);
        assertStatic(
                type,
                "create",
                Inventory.class,
                CoreRecord.class,
                TeamRecord.class,
                TeamProgress.class,
                CoreRepairCost.class,
                String.class,
                TeamResourceSnapshot.class,
                boolean.class);
        assertStatic(type, "stageLevelAt", OptionalLong.class, int.class);
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
