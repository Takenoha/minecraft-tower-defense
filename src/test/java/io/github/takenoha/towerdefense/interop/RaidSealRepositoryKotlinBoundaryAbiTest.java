package io.github.takenoha.towerdefense.interop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.persistence.Database;
import io.github.takenoha.towerdefense.persistence.OperationOutcome;
import io.github.takenoha.towerdefense.persistence.RaidSeal;
import io.github.takenoha.towerdefense.persistence.RaidSealRefundResult;
import io.github.takenoha.towerdefense.persistence.RaidSealRepository;
import io.github.takenoha.towerdefense.persistence.StartRequest;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin RaidSealRepository boundary. */
class RaidSealRepositoryKotlinBoundaryAbiTest {
    @Test
    void publicRepositoryMethodsKeepTheirJavaSignatures() throws Exception {
        assertNotNull(RaidSealRepository.class.getConstructor(Database.class));
        assertMethod(
                "register",
                RaidSeal.class,
                UUID.class,
                UUID.class,
                long.class,
                Instant.class);
        assertMethod(
                "reserve",
                OperationOutcome.class,
                UUID.class,
                UUID.class,
                UUID.class,
                long.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "consume",
                OperationOutcome.class,
                UUID.class,
                UUID.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "refund",
                RaidSealRefundResult.class,
                UUID.class,
                UUID.class,
                Instant.class);
        assertMethod("find", Optional.class, UUID.class);
        assertMethod("loadForOwner", List.class, UUID.class);
        assertMethod("loadAvailableRefunds", List.class, UUID.class);
    }

    @Test
    void transactionHooksRemainJavaStaticMethodsWithSqlExceptionDeclarations()
            throws Exception {
        assertHook(
                "consumeForStart",
                void.class,
                Connection.class,
                StartRequest.class);
        assertHook(
                "reserveForStart",
                void.class,
                Connection.class,
                StartRequest.class);
        assertHook(
                "consumeReservedForStart",
                OperationOutcome.class,
                Connection.class,
                UUID.class,
                UUID.class,
                Instant.class);
        assertHook(
                "refund",
                RaidSealRefundResult.class,
                Connection.class,
                UUID.class,
                UUID.class,
                Instant.class);
        assertHook(
                "refundIfPresent",
                Optional.class,
                Connection.class,
                UUID.class,
                UUID.class,
                Instant.class);
    }

    private static void assertMethod(String name, Class<?> returnType, Class<?>... parameterTypes)
            throws Exception {
        Method method = RaidSealRepository.class.getMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
    }

    private static void assertHook(String name, Class<?> returnType, Class<?>... parameterTypes)
            throws Exception {
        Method method = RaidSealRepository.class.getDeclaredMethod(name, parameterTypes);
        assertTrue(Modifier.isStatic(method.getModifiers()), name + " must be static");
        assertEquals(returnType, method.getReturnType(), name);
        assertArrayEquals(new Class<?>[] {SQLException.class}, method.getExceptionTypes(), name);
    }
}
