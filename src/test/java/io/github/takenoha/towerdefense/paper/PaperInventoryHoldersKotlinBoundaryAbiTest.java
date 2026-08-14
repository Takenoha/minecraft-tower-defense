package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin Paper inventory holders. */
class PaperInventoryHoldersKotlinBoundaryAbiTest {
    @Test
    void holderConstructorsAndInventoryHolderMethodsRemainCompatible() throws Exception {
        assertHolder(CoreManagementInventoryHolder.class, UUID.class);
        assertMethod(CoreManagementInventoryHolder.class, "coreId", UUID.class);
        assertAttach(CoreManagementInventoryHolder.class);

        assertHolder(TowerManagementInventoryHolder.class, UUID.class);
        assertMethod(TowerManagementInventoryHolder.class, "towerId", UUID.class);
        assertAttach(TowerManagementInventoryHolder.class);

        assertHolder(ResourceVaultInventoryHolder.class, UUID.class);
        assertMethod(ResourceVaultInventoryHolder.class, "coreId", UUID.class);
        assertAttach(ResourceVaultInventoryHolder.class);

        assertHolder(TeamManagementInventoryHolder.class, UUID.class);
        assertMethod(TeamManagementInventoryHolder.class, "coreId", UUID.class);
        assertMethod(TeamManagementInventoryHolder.class, "memberAt", Optional.class, int.class);
        assertMethod(
                TeamManagementInventoryHolder.class,
                "attachMemberSlots",
                void.class,
                Map.class);
        assertAttach(TeamManagementInventoryHolder.class);

        assertHolder(
                TeamManagementConfirmationHolder.class,
                UUID.class,
                UUID.class,
                TeamManagementConfirmationHolder.Action.class);
        assertMethod(TeamManagementConfirmationHolder.class, "coreId", UUID.class);
        assertMethod(TeamManagementConfirmationHolder.class, "targetId", UUID.class);
        assertMethod(
                TeamManagementConfirmationHolder.class,
                "action",
                TeamManagementConfirmationHolder.Action.class);
        assertAttach(TeamManagementConfirmationHolder.class);

        assertHolder(TowerResearchInventoryHolder.class, UUID.class);
        assertMethod(TowerResearchInventoryHolder.class, "coreId", UUID.class);
        assertAttach(TowerResearchInventoryHolder.class);
    }

    private static void assertHolder(Class<?> type, Class<?>... parameterTypes) throws Exception {
        assertTrue(Modifier.isPublic(type.getModifiers()), type.getName());
        assertTrue(Modifier.isFinal(type.getModifiers()), type.getName());
        assertTrue(InventoryHolder.class.isAssignableFrom(type), type.getName());
        Constructor<?> constructor = type.getConstructor(parameterTypes);
        assertTrue(Modifier.isPublic(constructor.getModifiers()), type.getName());
        Method getInventory = type.getMethod("getInventory");
        assertEquals(Inventory.class, getInventory.getReturnType(), type.getName());
        assertTrue(Modifier.isPublic(getInventory.getModifiers()), type.getName());
    }

    private static void assertAttach(Class<?> type) throws Exception {
        assertMethod(type, "attach", void.class, Inventory.class);
    }

    private static void assertMethod(
            Class<?> type,
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) throws Exception {
        Method method = type.getMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), type.getName() + "." + name);
        assertTrue(Modifier.isPublic(method.getModifiers()), type.getName() + "." + name);
        assertTrue(Modifier.isFinal(method.getModifiers()), type.getName() + "." + name);
    }
}
