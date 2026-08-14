package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin research-crystal inventory policy. */
class ResearchCrystalInventoryPolicyKotlinBoundaryAbiTest {
    @Test
    void policyBoundaryRemainsCompatible() throws Exception {
        Class<?> type = ResearchCrystalInventoryPolicy.class;
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        Constructor<?> constructor = type.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));

        Method scan = type.getMethod(
                "scan",
                ItemStack[].class,
                ItemStack.class,
                ResearchCrystalTagger.class);
        assertEquals(List.class, scan.getReturnType());
        assertTrue(Modifier.isPublic(scan.getModifiers()));
        assertTrue(Modifier.isStatic(scan.getModifiers()));

        Class<?> candidate = ResearchCrystalInventoryPolicy.Candidate.class;
        assertTrue(Record.class.isAssignableFrom(candidate));
        assertTrue(Modifier.isPublic(candidate.getModifiers()));
        assertTrue(Modifier.isFinal(candidate.getModifiers()));
        assertConstant(candidate, "OFF_HAND_SLOT", -1);
        assertPublicConstructor(
                candidate,
                int.class,
                ResearchCrystalItemIdentity.class,
                int.class,
                ItemStack.class);
        assertMethod(candidate, "storageSlot", int.class);
        assertMethod(candidate, "identity", ResearchCrystalItemIdentity.class);
        assertMethod(candidate, "quantity", int.class);
        assertMethod(candidate, "snapshot", ItemStack.class);
        assertMethod(candidate, "isOffHand", boolean.class);
    }

    private static void assertPublicConstructor(Class<?> type, Class<?>... parameterTypes)
            throws Exception {
        Constructor<?> constructor = type.getConstructor(parameterTypes);
        assertTrue(Modifier.isPublic(constructor.getModifiers()));
    }

    private static void assertMethod(Class<?> type, String name, Class<?> returnType)
            throws Exception {
        Method method = type.getMethod(name);
        assertEquals(returnType, method.getReturnType(), name);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
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
