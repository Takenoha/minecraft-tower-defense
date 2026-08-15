package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/** Java-facing ABI and behavior checks for the Kotlin receipt inventory planner. */
class ReceiptInventoryPlannerKotlinBoundaryAbiTest {
    @Test
    void plannerUtilityAndRecordBoundariesRemainCompatible() throws Exception {
        Class<?> planner = ReceiptInventoryPlanner.class;
        assertTrue(Modifier.isPublic(planner.getModifiers()));
        assertTrue(Modifier.isFinal(planner.getModifiers()));
        Constructor<?> plannerConstructor = planner.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(plannerConstructor.getModifiers()));

        assertStaticMethod(
                planner,
                "plan",
                Optional.class,
                ItemStack[].class,
                Predicate.class,
                long.class,
                String.class);
        assertStaticMethod(
                planner,
                "canApply",
                boolean.class,
                ItemStack[].class,
                List.class);
        assertRecord(ReceiptInventoryPlanner.Extraction.class,
                "slot", "amount", "original", "material");
    }

    @Test
    void emptyPlanningResultRemainsUnmodifiable() {
        ItemStack[] contents = new ItemStack[0];
        Optional<List<ReceiptInventoryPlanner.Extraction>> planned =
                ReceiptInventoryPlanner.plan(
                        contents,
                        item -> true,
                        0L,
                        "IRON_INGOT");

        assertTrue(planned.isPresent());
        assertTrue(planned.orElseThrow().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> planned.orElseThrow().clear());
    }

    @Test
    void quantityGuardsRemainCompatible() {
        ItemStack[] contents = new ItemStack[0];
        Predicate<ItemStack> source = item -> true;
        assertTrue(ReceiptInventoryPlanner.plan(contents, source, 0L, "IRON_INGOT")
                .orElseThrow().isEmpty());
        assertFalse(ReceiptInventoryPlanner.plan(
                contents, source, (long) Integer.MAX_VALUE + 1L, "IRON_INGOT").isPresent());
        assertThrows(IllegalArgumentException.class,
                () -> ReceiptInventoryPlanner.plan(contents, source, -1L, "IRON_INGOT"));
        assertFalse(ReceiptInventoryPlanner.plan(contents, source, 2L, "IRON_INGOT").isPresent());
        assertTrue(ReceiptInventoryPlanner.canApply(contents, List.of()));
    }

    private static void assertStaticMethod(
            Class<?> type,
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) throws Exception {
        Method method = type.getMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
        assertTrue(Modifier.isStatic(method.getModifiers()), name);
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
