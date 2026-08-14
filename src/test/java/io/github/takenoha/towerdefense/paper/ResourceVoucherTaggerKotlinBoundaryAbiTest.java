package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.persistence.ResourceVoucher;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin resource voucher tagger. */
class ResourceVoucherTaggerKotlinBoundaryAbiTest {
    @Test
    void constructorAndPublicMethodsRemainCompatible() throws Exception {
        Class<?> type = ResourceVoucherTagger.class;
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        Constructor<?> constructor = type.getConstructor(Plugin.class);
        assertTrue(Modifier.isPublic(constructor.getModifiers()));

        assertMethod(type, "create", ItemStack.class, ResourceVoucher.class);
        assertMethod(type, "tagDelivery", ItemStack.class, ItemStack.class, UUID.class);
        assertMethod(type, "tagRedeem", ItemStack.class, ItemStack.class, UUID.class);
        assertMethod(type, "stripReceipts", ItemStack.class, ItemStack.class);
        assertMethod(type, "stripRedeemReceipt", ItemStack.class, ItemStack.class, UUID.class);
        assertMethod(type, "read", Optional.class, ItemStack.class);
        assertMethod(type, "isVoucher", boolean.class, ItemStack.class);
        assertMethod(type, "isFor", boolean.class, ItemStack.class, UUID.class);
        assertMethod(type, "isDeliveryReceipt", boolean.class, ItemStack.class);
        assertMethod(type, "isRedeemReceipt", boolean.class, ItemStack.class);
        assertMethod(type, "matchesCanonical", boolean.class, ItemStack.class, ResourceVoucher.class);
    }

    private static void assertMethod(
            Class<?> type,
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) throws Exception {
        Method method = type.getMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
        assertTrue(Modifier.isFinal(method.getModifiers()), name);
    }
}
