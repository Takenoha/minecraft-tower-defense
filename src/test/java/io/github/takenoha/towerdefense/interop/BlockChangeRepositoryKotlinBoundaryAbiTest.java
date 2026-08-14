package io.github.takenoha.towerdefense.interop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.persistence.BlockChange;
import io.github.takenoha.towerdefense.persistence.BlockChangeRepository;
import io.github.takenoha.towerdefense.persistence.BlockRollbackDecision;
import io.github.takenoha.towerdefense.persistence.Database;
import io.github.takenoha.towerdefense.persistence.OperationOutcome;
import io.github.takenoha.towerdefense.persistence.PreparedRollback;
import io.github.takenoha.towerdefense.persistence.StoredBlockChange;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin BlockChangeRepository boundary. */
class BlockChangeRepositoryKotlinBoundaryAbiTest {
    @Test
    void publicRepositoryMethodsKeepTheirJavaSignatures() throws Exception {
        assertNotNull(BlockChangeRepository.class.getConstructor(Database.class));
        assertMethod(
                "prepare",
                OperationOutcome.class,
                BlockChange.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "prepareApply",
                OperationOutcome.class,
                UUID.class,
                UUID.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "nextGeneration",
                long.class,
                UUID.class,
                UUID.class,
                int.class,
                int.class,
                int.class);
        assertMethod(
                "apply",
                OperationOutcome.class,
                UUID.class,
                UUID.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "prepareRollback",
                OperationOutcome.class,
                UUID.class,
                UUID.class,
                UUID.class,
                BlockRollbackDecision.class,
                Instant.class);
        assertMethod(
                "applyRollback",
                OperationOutcome.class,
                UUID.class,
                UUID.class,
                UUID.class,
                BlockRollbackDecision.class,
                Instant.class);
        assertMethod("loadChanges", List.class, UUID.class);
        assertMethod("loadUnresolvedChanges", List.class, UUID.class);
        assertMethod("countUnresolvedTemporaryBlocks", long.class, UUID.class);
        assertMethod("loadPreparedRollback", Optional.class, UUID.class, UUID.class);
    }

    @Test
    void transactionHooksRemainJavaStaticMethodsWithSqlExceptionDeclarations()
            throws Exception {
        assertHook("hasUnresolved", boolean.class, Connection.class, UUID.class);
        assertHook(
                "settleAppliedEventBlocks",
                void.class,
                Connection.class,
                UUID.class,
                UUID.class,
                Instant.class);
    }

    private static void assertMethod(String name, Class<?> returnType, Class<?>... parameterTypes)
            throws Exception {
        Method method = BlockChangeRepository.class.getMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
    }

    private static void assertHook(String name, Class<?> returnType, Class<?>... parameterTypes)
            throws Exception {
        Method method = BlockChangeRepository.class.getDeclaredMethod(name, parameterTypes);
        assertTrue(Modifier.isStatic(method.getModifiers()), name + " must be static");
        assertTrue(Modifier.isPublic(method.getModifiers()), name + " must be public");
        assertEquals(returnType, method.getReturnType(), name);
        assertArrayEquals(new Class<?>[] {SQLException.class}, method.getExceptionTypes(), name);
    }
}
