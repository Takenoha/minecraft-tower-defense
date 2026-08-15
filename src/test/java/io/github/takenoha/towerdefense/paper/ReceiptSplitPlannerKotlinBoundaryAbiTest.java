package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/** Java-facing ABI and invariant checks for the Kotlin receipt split planner. */
class ReceiptSplitPlannerKotlinBoundaryAbiTest {
    @Test
    void plannerUtilityAndRecordBoundariesRemainCompatible() throws Exception {
        Class<?> planner = ReceiptSplitPlanner.class;
        assertTrue(Modifier.isPublic(planner.getModifiers()));
        assertTrue(Modifier.isFinal(planner.getModifiers()));
        Constructor<?> plannerConstructor = planner.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(plannerConstructor.getModifiers()));

        Method canApply = planner.getMethod("canApply", java.util.List.class, java.util.List.class);
        assertEquals(boolean.class, canApply.getReturnType());
        assertTrue(Modifier.isPublic(canApply.getModifiers()));
        assertTrue(Modifier.isStatic(canApply.getModifiers()));

        assertRecord(ReceiptSplitPlanner.Stack.class, "key", "amount", "maxStackSize");
        assertRecord(ReceiptSplitPlanner.Split.class, "slot", "amount", "key");
    }

    @Test
    void recordValidationAndCapacitySimulationRemainCompatible() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReceiptSplitPlanner.Stack("IRON_INGOT", 0, 64));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReceiptSplitPlanner.Split(-1, 1, "IRON_INGOT"));

        java.util.List<ReceiptSplitPlanner.Stack> contents = new java.util.ArrayList<>();
        contents.add(new ReceiptSplitPlanner.Stack("IRON_INGOT", 8, 64));
        contents.add(new ReceiptSplitPlanner.Stack("IRON_INGOT", 60, 64));
        while (contents.size() < 36) {
            contents.add(null);
        }
        java.util.List<ReceiptSplitPlanner.Split> plan = java.util.List.of(
                new ReceiptSplitPlanner.Split(0, 3, "IRON_INGOT"));
        assertTrue(ReceiptSplitPlanner.canApply(contents, plan));
    }

    private static void assertRecord(Class<?> type, String... names) throws Exception {
        assertTrue(type.isRecord());
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertEquals(names.length, type.getRecordComponents().length);
        for (int index = 0; index < names.length; index++) {
            assertEquals(names[index], type.getRecordComponents()[index].getName());
            assertEquals(type.getRecordComponents()[index].getType(),
                    type.getMethod(names[index]).getReturnType());
        }
    }
}
