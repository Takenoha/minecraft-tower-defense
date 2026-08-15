package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.tactical.TacticalCandidateSet;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin tactical selection GUI builder. */
class TacticalBuildSelectionGuiKotlinBoundaryAbiTest {
    @Test
    void guiBoundaryRemainsCompatible() throws Exception {
        Class<?> type = TacticalBuildSelectionGui.class;
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));

        Constructor<?> constructor = type.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));

        assertConstant(type, "SIZE", 27);
        assertConstant(type, "CONFIRM_SLOT", 22);
        assertConstant(type, "CLOSE_SLOT", 26);
        assertArray(type, "CANDIDATE_SLOTS", new int[] {11, 13, 15});
        assertArray(type, "BRANCH_SLOTS", new int[] {3, 5});

        assertStatic(type, "create", Inventory.class, TacticalBuildSelectionInventoryHolder.class);
        assertStatic(
                type,
                "refresh",
                void.class,
                Inventory.class,
                TacticalCandidateSet.class,
                String.class);
        assertStatic(
                type,
                "refresh",
                void.class,
                Inventory.class,
                TacticalCandidateSet.class,
                String.class,
                String.class);
        assertStatic(type, "candidateIndexAt", int.class, int.class);
        assertStatic(type, "branchIndexAt", int.class, int.class);

        assertEquals(0, TacticalBuildSelectionGui.candidateIndexAt(11));
        assertEquals(2, TacticalBuildSelectionGui.candidateIndexAt(15));
        assertEquals(-1, TacticalBuildSelectionGui.candidateIndexAt(12));
        assertEquals(0, TacticalBuildSelectionGui.branchIndexAt(3));
        assertEquals(1, TacticalBuildSelectionGui.branchIndexAt(5));
        assertEquals(-1, TacticalBuildSelectionGui.branchIndexAt(4));
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

    private static void assertArray(Class<?> type, String name, int[] expected) throws Exception {
        Field field = type.getField(name);
        assertEquals(int[].class, field.getType(), name);
        assertTrue(Modifier.isPublic(field.getModifiers()), name);
        assertTrue(Modifier.isStatic(field.getModifiers()), name);
        assertTrue(Modifier.isFinal(field.getModifiers()), name);
        assertArrayEquals(expected, (int[]) field.get(null), name);
    }
}
