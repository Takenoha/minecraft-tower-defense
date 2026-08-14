package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin Paper policy slice. */
class PaperPoliciesKotlinBoundaryAbiTest {
    @Test
    void staticPolicyBoundariesKeepMethodsAndConstants() throws Exception {
        assertStaticBoolean(
                VoucherEntityPolicy.class,
                "blocksInteraction",
                boolean.class,
                boolean.class,
                boolean.class);
        assertStaticBoolean(
                VoucherEntityPolicy.class,
                "blocksHangingBreak",
                boolean.class,
                boolean.class);
        assertStaticBoolean(
                VoucherContainerPolicy.class,
                "blocksPlainVoucherInsertion",
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class);
        assertStaticBoolean(
                RaidSealMaterialPolicy.class,
                "supports",
                boolean.class,
                String.class);
        assertStaticBoolean(
                RaidSealMaterialPolicy.class,
                "isLegacy",
                boolean.class,
                String.class);
        assertStaticBoolean(
                RaidSealAutomationPolicy.class,
                "cancelRightClick",
                boolean.class,
                boolean.class,
                boolean.class);
        assertStaticBoolean(
                RaidSealAutomationPolicy.class,
                "cancelCrafter",
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class);

        assertEquals("ECHO_SHARD", RaidSealMaterialPolicy.class.getField("CURRENT_MATERIAL").get(null));
        assertEquals("ENDER_EYE", RaidSealMaterialPolicy.class.getField("LEGACY_MATERIAL").get(null));
        assertTrue(Modifier.isPrivate(VoucherEntityPolicy.class.getDeclaredConstructor().getModifiers()));
        assertTrue(Modifier.isPrivate(VoucherContainerPolicy.class.getDeclaredConstructor().getModifiers()));
        assertTrue(Modifier.isPrivate(RaidSealMaterialPolicy.class.getDeclaredConstructor().getModifiers()));
        assertTrue(Modifier.isPrivate(RaidSealAutomationPolicy.class.getDeclaredConstructor().getModifiers()));
    }

    @Test
    void recoveryGuardKeepsPackageBoundaryMethodsAndStateSemantics() throws Exception {
        PlayerRecoveryGuard guard = new PlayerRecoveryGuard();
        UUID playerId = UUID.randomUUID();
        assertEquals(boolean.class, PlayerRecoveryGuard.class.getMethod("isGuarded", UUID.class).getReturnType());
        assertEquals(void.class, PlayerRecoveryGuard.class.getMethod("begin", UUID.class).getReturnType());
        assertEquals(void.class, PlayerRecoveryGuard.class.getMethod("complete", UUID.class).getReturnType());
        assertTrue(Modifier.isFinal(PlayerRecoveryGuard.class.getModifiers()));
        assertTrue(!guard.isGuarded(playerId));
        guard.begin(playerId);
        assertTrue(guard.isGuarded(playerId));
        guard.complete(playerId);
        assertTrue(!guard.isGuarded(playerId));
    }

    private static void assertStaticBoolean(
            Class<?> type, String name, Class<?> returnType, Class<?>... parameterTypes) throws Exception {
        var method = type.getMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
        assertTrue(Modifier.isStatic(method.getModifiers()), name);
    }
}
