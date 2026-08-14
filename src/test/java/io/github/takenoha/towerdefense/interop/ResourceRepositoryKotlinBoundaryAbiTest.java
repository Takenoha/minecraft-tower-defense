package io.github.takenoha.towerdefense.interop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.DefensePhase;
import io.github.takenoha.towerdefense.persistence.Database;
import io.github.takenoha.towerdefense.persistence.OperationOutcome;
import io.github.takenoha.towerdefense.persistence.ResourceMutationResult;
import io.github.takenoha.towerdefense.persistence.ResourcePickupFeedback;
import io.github.takenoha.towerdefense.persistence.ResourceRepository;
import io.github.takenoha.towerdefense.persistence.ResourceType;
import io.github.takenoha.towerdefense.persistence.TeamResourceSettlement;
import io.github.takenoha.towerdefense.persistence.TeamResourceSnapshot;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin ResourceRepository boundary. */
class ResourceRepositoryKotlinBoundaryAbiTest {
    @Test
    void publicRepositoryMethodsKeepTheirJavaSignatures() throws Exception {
        assertNotNull(ResourceRepository.class.getConstructor(Database.class));
        assertMethod("load", TeamResourceSnapshot.class, UUID.class, UUID.class);
        assertMethod(
                "loadTerminalSettlement",
                TeamResourceSettlement.class,
                UUID.class,
                DefensePhase.class);
        assertMethod(
                "loadPickupFeedback",
                ResourcePickupFeedback.class,
                UUID.class,
                UUID.class,
                ResourceType.class,
                int.class);
        assertMethod(
                "credit",
                ResourceMutationResult.class,
                UUID.class,
                ResourceType.class,
                long.class,
                UUID.class,
                String.class,
                Instant.class);
        assertMethod(
                "debit",
                ResourceMutationResult.class,
                UUID.class,
                UUID.class,
                ResourceType.class,
                long.class,
                UUID.class,
                String.class,
                Instant.class);
    }

    @Test
    void transactionHooksRemainJavaStaticMethodsWithSqlExceptionDeclarations()
            throws Exception {
        assertHook(
                "loadPickupFeedback",
                ResourcePickupFeedback.class,
                Connection.class,
                UUID.class,
                UUID.class,
                ResourceType.class,
                int.class);
        assertHook(
                "settleForTerminal",
                void.class,
                Connection.class,
                UUID.class,
                UUID.class,
                DefensePhase.class,
                Instant.class);
        assertHook(
                "settleForRecovery",
                void.class,
                Connection.class,
                UUID.class,
                UUID.class,
                Instant.class);
        assertHook(
                "debitInTransaction",
                OperationOutcome.class,
                Connection.class,
                UUID.class,
                UUID.class,
                ResourceType.class,
                long.class,
                UUID.class,
                String.class,
                String.class,
                Instant.class);
        assertHook(
                "creditInTransaction",
                OperationOutcome.class,
                Connection.class,
                UUID.class,
                ResourceType.class,
                long.class,
                UUID.class,
                String.class,
                String.class,
                Instant.class);
        assertHook("balance", long.class, Connection.class, UUID.class, ResourceType.class);
        assertHook("loadEventTeam", UUID.class, Connection.class, UUID.class);
        assertHook("requireTeamMember", void.class, Connection.class, UUID.class, UUID.class);
    }

    @Test
    void walletItemProbeRemainsAJavaStaticMethodAndAcceptsNullAsNotWalletResource()
            throws Exception {
        Method method = ResourceRepository.class.getDeclaredMethod("isWalletResource", String.class);
        assertTrue(Modifier.isStatic(method.getModifiers()));
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertEquals(boolean.class, method.getReturnType());
        assertTrue(!ResourceRepository.isWalletResource(null));
        assertTrue(ResourceRepository.isWalletResource("defense_shard"));
    }

    private static void assertMethod(String name, Class<?> returnType, Class<?>... parameterTypes)
            throws Exception {
        Method method = ResourceRepository.class.getMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
    }

    private static void assertHook(String name, Class<?> returnType, Class<?>... parameterTypes)
            throws Exception {
        Method method = ResourceRepository.class.getDeclaredMethod(name, parameterTypes);
        assertTrue(Modifier.isStatic(method.getModifiers()), name + " must be static");
        assertTrue(Modifier.isPublic(method.getModifiers()), name + " must be public");
        assertEquals(returnType, method.getReturnType(), name);
        assertArrayEquals(new Class<?>[] {SQLException.class}, method.getExceptionTypes(), name);
    }
}
