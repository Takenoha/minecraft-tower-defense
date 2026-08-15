package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.tactical.TacticalCandidateSet;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin tactical selection inventory holder. */
class TacticalBuildSelectionInventoryHolderKotlinBoundaryAbiTest {
    @Test
    void holderBoundaryRemainsCompatible() throws Exception {
        Class<?> type = TacticalBuildSelectionInventoryHolder.class;
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertTrue(InventoryHolder.class.isAssignableFrom(type));

        Constructor<?> constructor = type.getConstructor(
                UUID.class,
                UUID.class,
                long.class,
                UUID.class,
                UUID.class,
                TacticalCandidateSet.class);
        assertTrue(Modifier.isPublic(constructor.getModifiers()));

        assertMethod(type, "tacticalSessionId", UUID.class);
        assertMethod(type, "coreId", UUID.class);
        assertMethod(type, "stage", long.class);
        assertMethod(type, "sealId", UUID.class);
        assertMethod(type, "ownerId", UUID.class);
        assertMethod(type, "candidates", TacticalCandidateSet.class);
        assertMethod(type, "selectedBuildId", Optional.class);
        assertMethod(type, "selectedBranchId", Optional.class);
        assertMethod(type, "branchRequired", boolean.class);
        assertMethod(type, "select", void.class, String.class);
        assertMethod(type, "selectBranch", void.class, String.class);
        assertMethod(type, "markConfirming", void.class);
        assertMethod(type, "confirming", boolean.class);
        assertMethod(type, "attach", void.class, Inventory.class);
        assertMethod(type, "getInventory", Inventory.class);
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
