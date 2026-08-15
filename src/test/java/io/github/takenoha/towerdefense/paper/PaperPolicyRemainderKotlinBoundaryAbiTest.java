package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.persistence.PaymentMode;
import java.lang.reflect.Modifier;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the remaining Kotlin Paper policy helpers. */
class PaperPolicyRemainderKotlinBoundaryAbiTest {
    @Test
    void staticMethodsConstantsAndPrivateConstructorsRemainCompatible() throws Exception {
        assertStatic(CoreMaterialPolicy.class, "isCoreItemMaterial", boolean.class, Material.class);
        assertStatic(CoreMaterialPolicy.class, "isLegacyItemMaterial", boolean.class, Material.class);
        assertStatic(CoreMaterialPolicy.class, "isCoreBlockMaterial", boolean.class, Material.class);
        assertStatic(CoreMaterialPolicy.class, "isCurrentBlock", boolean.class, Material.class);
        assertStatic(CoreMaterialPolicy.class, "requireCoreItemMaterial", void.class, Material.class);
        assertEquals(Material.RESIN_BRICKS, CoreMaterialPolicy.class.getField("CURRENT_ITEM").get(null));
        assertEquals(Material.NETHER_STAR, CoreMaterialPolicy.class.getField("LEGACY_ITEM").get(null));
        assertEquals(Material.DRIED_KELP_BLOCK, CoreMaterialPolicy.class.getField("CURRENT_BLOCK").get(null));
        assertEquals(Material.BEACON, CoreMaterialPolicy.class.getField("LEGACY_BLOCK").get(null));

        assertStatic(PaymentSelectionPolicy.class, "choose", PaymentMode.class, boolean.class, boolean.class, boolean.class);
        assertStatic(
                ReceiptTransferPolicy.class,
                "containsTagged",
                boolean.class,
                Predicate.class,
                Object.class,
                Object.class,
                Object.class);
        assertStatic(
                ReceiptTransferPolicy.class,
                "containsTagged",
                boolean.class,
                Predicate.class,
                Object.class,
                Object.class,
                Object.class,
                Object.class);
        assertStatic(
                VoucherReceiptRecoveryPolicy.class,
                "isMatchingRedeemReceipt",
                boolean.class,
                ResourceVoucherItemData.class,
                java.util.UUID.class,
                java.util.UUID.class);
        assertStatic(
                VoucherReceiptRecoveryPolicy.class,
                "stripsRolledBackReceipt",
                boolean.class,
                io.github.takenoha.towerdefense.persistence.VoucherRedeemState.class,
                io.github.takenoha.towerdefense.persistence.ResourceVoucherState.class);

        assertTrue(Modifier.isPrivate(CoreMaterialPolicy.class.getDeclaredConstructor().getModifiers()));
        assertTrue(Modifier.isPrivate(PaymentSelectionPolicy.class.getDeclaredConstructor().getModifiers()));
        assertTrue(Modifier.isPrivate(ReceiptTransferPolicy.class.getDeclaredConstructor().getModifiers()));
        assertTrue(Modifier.isPrivate(VoucherReceiptRecoveryPolicy.class.getDeclaredConstructor().getModifiers()));
    }

    @Test
    void nullableMaterialChecksRetainLegacyNullBehavior() {
        assertFalse(CoreMaterialPolicy.isCoreItemMaterial(null));
        assertFalse(CoreMaterialPolicy.isLegacyItemMaterial(null));
        assertFalse(CoreMaterialPolicy.isCoreBlockMaterial(null));
        assertFalse(CoreMaterialPolicy.isCurrentBlock(null));
    }

    private static void assertStatic(
            Class<?> type, String name, Class<?> returnType, Class<?>... parameterTypes) throws Exception {
        var method = type.getMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
        assertTrue(Modifier.isStatic(method.getModifiers()), name);
    }
}
