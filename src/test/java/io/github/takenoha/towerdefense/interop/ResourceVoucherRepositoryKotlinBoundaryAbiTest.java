package io.github.takenoha.towerdefense.interop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.persistence.Database;
import io.github.takenoha.towerdefense.persistence.OperationOutcome;
import io.github.takenoha.towerdefense.persistence.ResourceType;
import io.github.takenoha.towerdefense.persistence.ResourceVoucherRepository;
import io.github.takenoha.towerdefense.persistence.VoucherDeliveryResult;
import io.github.takenoha.towerdefense.persistence.VoucherRedeemResult;
import io.github.takenoha.towerdefense.persistence.VoucherWithdrawalResult;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin ResourceVoucherRepository boundary. */
class ResourceVoucherRepositoryKotlinBoundaryAbiTest {
    @Test
    void publicRepositoryMethodsKeepTheirJavaSignatures() throws Exception {
        assertNotNull(ResourceVoucherRepository.class.getConstructor(Database.class));
        assertMethod(
                "withdraw",
                VoucherWithdrawalResult.class,
                UUID.class,
                UUID.class,
                ResourceType.class,
                long.class,
                UUID.class,
                Instant.class);
        assertMethod("findVoucher", Optional.class, UUID.class);
        assertMethod("loadPendingDeliveries", List.class, UUID.class);
        assertMethod("loadOpenDeliveryOperations", List.class, UUID.class);
        assertMethod("findDeliveryOperation", Optional.class, UUID.class);
        assertMethod(
                "prepareDelivery",
                VoucherDeliveryResult.class,
                UUID.class,
                UUID.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "applyDelivery",
                OperationOutcome.class,
                UUID.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "rollbackDelivery",
                OperationOutcome.class,
                UUID.class,
                Instant.class);
        assertMethod("loadOpenRedeems", List.class, UUID.class);
        assertMethod("loadRedeemsForRecovery", List.class, UUID.class);
        assertMethod("findRedeemOperation", Optional.class, UUID.class);
        assertMethod(
                "prepareRedeem",
                VoucherRedeemResult.class,
                UUID.class,
                UUID.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "applyRedeem",
                OperationOutcome.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "rollbackRedeem",
                OperationOutcome.class,
                UUID.class,
                Instant.class);
        assertMethod("hasLiveVouchers", boolean.class, UUID.class);
    }

    @Test
    void packageTransactionHookRemainsAJavaStaticMethodWithSqlExceptionDeclaration()
            throws Exception {
        Method method = ResourceVoucherRepository.class.getDeclaredMethod(
                "hasLiveVouchers", Connection.class, UUID.class);
        assertTrue(Modifier.isStatic(method.getModifiers()));
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertEquals(boolean.class, method.getReturnType());
        assertArrayEquals(new Class<?>[] {SQLException.class}, method.getExceptionTypes());
    }

    private static void assertMethod(String name, Class<?> returnType, Class<?>... parameterTypes)
            throws Exception {
        Method method = ResourceVoucherRepository.class.getMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
    }
}
