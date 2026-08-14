package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.persistence.EscrowRepository;
import io.github.takenoha.towerdefense.runtime.DatabaseExecutor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin reward queue delivery manager. */
class RewardQueueDeliveryManagerKotlinBoundaryAbiTest {
    @Test
    void managerBoundaryRemainsCompatible() throws Exception {
        Class<?> type = RewardQueueDeliveryManager.class;
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertTrue(AutoCloseable.class.isAssignableFrom(type));

        Class<?>[] fourArgument = {
                Plugin.class,
                EscrowRepository.class,
                DatabaseExecutor.class,
                RewardQueueReceiptTagger.class,
        };
        Constructor<?> fourArgumentConstructor = type.getConstructor(fourArgument);
        assertTrue(Modifier.isPublic(fourArgumentConstructor.getModifiers()));

        Class<?>[] fiveArgument = {
                Plugin.class,
                EscrowRepository.class,
                DatabaseExecutor.class,
                RewardQueueReceiptTagger.class,
                ResearchCrystalTagger.class,
        };
        Constructor<?> fiveArgumentConstructor = type.getConstructor(fiveArgument);
        assertTrue(Modifier.isPublic(fiveArgumentConstructor.getModifiers()));

        assertPublicMethod(type, "onPlayerJoin", void.class, Player.class);
        assertPublicMethod(type, "onPlayerQuit", void.class, Player.class);
        assertPublicMethod(type, "onEventSettled", void.class, UUID.class);
        assertPublicMethod(type, "tagger", RewardQueueReceiptTagger.class);
        assertPublicMethod(type, "close", void.class);

        assertPrivateStatic(type, "deterministicDeliveryOperation", UUID.class, UUID.class, UUID.class);
        assertPrivateStatic(type, "rootCause", Throwable.class, Throwable.class);
        assertPrivateStatic(type, "requireMainThread", void.class);

        Method request = type.getDeclaredMethod("request", Player.class);
        assertTrue(Modifier.isPrivate(request.getModifiers()));
        assertEquals(void.class, request.getReturnType());

        Method runOnMainThread = type.getDeclaredMethod("runOnMainThread", Runnable.class);
        assertTrue(Modifier.isPrivate(runOnMainThread.getModifiers()));
        assertEquals(void.class, runOnMainThread.getReturnType());
    }

    private static void assertPublicMethod(
            Class<?> type,
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) throws Exception {
        Method method = type.getMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
    }

    private static void assertPrivateStatic(
            Class<?> type,
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) throws Exception {
        Method method = type.getDeclaredMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
        assertTrue(Modifier.isPrivate(method.getModifiers()), name);
        assertTrue(Modifier.isStatic(method.getModifiers()), name);
    }
}
