package io.github.takenoha.towerdefense.interop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.DefensePhase;
import io.github.takenoha.towerdefense.persistence.Database;
import io.github.takenoha.towerdefense.persistence.EscrowClaimResult;
import io.github.takenoha.towerdefense.persistence.EscrowDrop;
import io.github.takenoha.towerdefense.persistence.EscrowRepository;
import io.github.takenoha.towerdefense.persistence.OperationOutcome;
import io.github.takenoha.towerdefense.persistence.RewardDeliveryOutcome;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin EscrowRepository boundary. */
class EscrowRepositoryKotlinBoundaryAbiTest {
    @Test
    void publicRepositoryMethodsKeepTheirJavaSignatures() throws Exception {
        assertNotNull(EscrowRepository.class.getConstructor(Database.class));
        assertMethod(
                "prepare",
                OperationOutcome.class,
                EscrowDrop.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "updateDisplayEntity",
                void.class,
                UUID.class,
                UUID.class,
                Optional.class,
                Instant.class);
        assertMethod("clearDisplayEntity", void.class, UUID.class, UUID.class, Instant.class);
        assertMethod(
                "voidPreparedDrop",
                OperationOutcome.class,
                UUID.class,
                UUID.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "prepareClaim",
                OperationOutcome.class,
                UUID.class,
                UUID.class,
                UUID.class,
                int.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "applyClaim",
                EscrowClaimResult.class,
                UUID.class,
                UUID.class,
                UUID.class,
                int.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "claim",
                EscrowClaimResult.class,
                UUID.class,
                UUID.class,
                UUID.class,
                int.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "settleEvent",
                OperationOutcome.class,
                UUID.class,
                UUID.class,
                DefensePhase.class,
                Instant.class);
        assertMethod(
                "settleEvent",
                OperationOutcome.class,
                UUID.class,
                UUID.class,
                DefensePhase.class,
                Instant.class,
                Duration.class);
        assertMethod("voidEvent", OperationOutcome.class, UUID.class, UUID.class, Instant.class);
        assertMethod("loadDrops", List.class, UUID.class);
        assertMethod("loadClaims", List.class, UUID.class);
        assertMethod("loadRewardQueue", List.class, UUID.class);
        assertMethod("loadPendingRewardQueueForPlayer", List.class, UUID.class);
        assertMethod(
                "loadPendingRewardQueueForPlayer",
                List.class,
                UUID.class,
                Instant.class);
        assertMethod("findRewardQueue", Optional.class, UUID.class);
        assertMethod(
                "prepareRewardDelivery",
                RewardDeliveryOutcome.class,
                UUID.class,
                UUID.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "markRewardDelivered",
                OperationOutcome.class,
                UUID.class,
                UUID.class,
                UUID.class,
                Instant.class);
    }

    @Test
    void terminalTransactionHooksRemainJavaStaticMethodsWithSqlExceptionDeclarations()
            throws Exception {
        assertHook(
                "settleForTerminal",
                void.class,
                Connection.class,
                UUID.class,
                UUID.class,
                DefensePhase.class,
                Instant.class);
        assertHook(
                "settleForTerminal",
                void.class,
                Connection.class,
                UUID.class,
                UUID.class,
                DefensePhase.class,
                Instant.class,
                Duration.class);
        assertHook(
                "voidForRecovery",
                void.class,
                Connection.class,
                UUID.class,
                UUID.class,
                Instant.class);
    }

    private static void assertMethod(String name, Class<?> returnType, Class<?>... parameterTypes)
            throws Exception {
        Method method = EscrowRepository.class.getMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
    }

    private static void assertHook(String name, Class<?> returnType, Class<?>... parameterTypes)
            throws Exception {
        Method method = EscrowRepository.class.getDeclaredMethod(name, parameterTypes);
        assertTrue(Modifier.isStatic(method.getModifiers()), name + " must be static");
        assertTrue(Modifier.isPublic(method.getModifiers()), name + " must be public");
        assertEquals(returnType, method.getReturnType(), name);
        assertArrayEquals(new Class<?>[] {SQLException.class}, method.getExceptionTypes(), name);
    }
}
