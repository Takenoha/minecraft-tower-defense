package io.github.takenoha.towerdefense.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceVoucherRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-05T11:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void voucherWithdrawalDeliveryAndRedeemAreIdempotent() {
        Database database = new Database(temporaryDirectory.resolve("voucher.sqlite"));
        DefenseRepository teams = new DefenseRepository(database);
        ResourceRepository resources = new ResourceRepository(database);
        ResourceVoucherRepository vouchers = new ResourceVoucherRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        teams.createSoloTeam(teamId, ownerId, NOW);
        resources.credit(
                teamId,
                ResourceType.DEFENSE_POINTS,
                10L,
                UUID.randomUUID(),
                "voucher-seed",
                NOW);

        UUID withdrawal = UUID.randomUUID();
        VoucherWithdrawalResult issued = vouchers.withdraw(
                teamId,
                ownerId,
                ResourceType.DEFENSE_POINTS,
                10L,
                withdrawal,
                NOW.plusSeconds(1L));
        assertEquals(OperationOutcome.APPLIED, issued.outcome());
        assertEquals(ResourceVoucherState.PENDING_DELIVERY, issued.voucher().state());
        assertEquals(0L, resources.load(teamId, ownerId).balance(ResourceType.DEFENSE_POINTS));
        assertEquals(
                OperationOutcome.ALREADY_APPLIED,
                vouchers.withdraw(
                        teamId,
                        ownerId,
                        ResourceType.DEFENSE_POINTS,
                        10L,
                        withdrawal,
                        NOW.plusSeconds(2L)).outcome());

        UUID delivery = UUID.randomUUID();
        VoucherDeliveryResult prepared = vouchers.prepareDelivery(
                issued.voucher().voucherId(), ownerId, delivery, NOW.plusSeconds(3L));
        assertEquals(VoucherDeliveryOutcome.PREPARED, prepared.outcome());
        assertEquals(
                VoucherDeliveryOutcome.ALREADY_PREPARED,
                vouchers.prepareDelivery(
                                issued.voucher().voucherId(), ownerId, delivery, NOW.plusSeconds(4L))
                        .outcome());
        assertEquals(
                OperationOutcome.APPLIED,
                vouchers.applyDelivery(
                        issued.voucher().voucherId(), delivery, NOW.plusSeconds(5L)));
        assertEquals(
                OperationOutcome.ALREADY_APPLIED,
                vouchers.applyDelivery(
                        issued.voucher().voucherId(), delivery, NOW.plusSeconds(6L)));
        assertEquals(
                ResourceVoucherState.AVAILABLE,
                vouchers.findVoucher(issued.voucher().voucherId()).orElseThrow().state());

        UUID redeem = UUID.randomUUID();
        VoucherRedeemResult reserved = vouchers.prepareRedeem(
                issued.voucher().voucherId(), ownerId, redeem, NOW.plusSeconds(7L));
        assertEquals(OperationOutcome.APPLIED, reserved.outcome());
        assertEquals(ResourceVoucherState.RESERVED, reserved.voucher().state());
        assertEquals(
                OperationOutcome.APPLIED,
                vouchers.applyRedeem(redeem, NOW.plusSeconds(8L)));
        assertEquals(OperationOutcome.ALREADY_APPLIED, vouchers.applyRedeem(redeem, NOW.plusSeconds(9L)));
        assertEquals(
                ResourceVoucherState.REDEEMED,
                vouchers.findVoucher(issued.voucher().voucherId()).orElseThrow().state());
        assertEquals(10L, resources.load(teamId, ownerId).balance(ResourceType.DEFENSE_POINTS));
        resources.debit(
                teamId,
                ownerId,
                ResourceType.DEFENSE_POINTS,
                10L,
                UUID.randomUUID(),
                "voucher-spend-before-delete",
                NOW.plusSeconds(10L));
        assertEquals(
                ManagementOutcome.APPLIED,
                teams.disbandTeam(teamId, ownerId, UUID.randomUUID(), NOW.plusSeconds(11L)).outcome());
        assertTrue(teams.findTeam(teamId).isEmpty());
    }

    @Test
    void deliveryRecipientAndPayloadAreBoundAndLiveVoucherBlocksTeamDeletion() {
        Database database = new Database(temporaryDirectory.resolve("voucher-guards.sqlite"));
        DefenseRepository teams = new DefenseRepository(database);
        ResourceRepository resources = new ResourceRepository(database);
        ResourceVoucherRepository vouchers = new ResourceVoucherRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID outsiderId = UUID.randomUUID();
        teams.createSoloTeam(teamId, ownerId, NOW);
        resources.credit(
                teamId,
                ResourceType.ENHANCEMENT_POINTS,
                2L,
                UUID.randomUUID(),
                "voucher-guard-seed",
                NOW);
        ResourceVoucher voucher = vouchers.withdraw(
                        teamId,
                        ownerId,
                        ResourceType.ENHANCEMENT_POINTS,
                        2L,
                        UUID.randomUUID(),
                        NOW.plusSeconds(1L))
                .voucher();

        assertThrows(
                PersistenceConflictException.class,
                () -> vouchers.prepareDelivery(
                        voucher.voucherId(), outsiderId, UUID.randomUUID(), NOW.plusSeconds(2L)));
        assertTrue(vouchers.hasLiveVouchers(teamId));
        assertThrows(
                PersistenceConflictException.class,
                () -> teams.disbandTeam(teamId, ownerId, UUID.randomUUID(), NOW.plusSeconds(3L)));

        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT version FROM schema_migrations ORDER BY version DESC LIMIT 1");
                ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next());
            assertEquals(SchemaMigrator.CURRENT_VERSION, resultSet.getInt(1));
        } catch (Exception exception) {
            throw new AssertionError("Could not inspect voucher migration", exception);
        }
    }

    @Test
    void rolledBackDeliveryCanRetryAndRolledBackRedeemCannotReplay() {
        Database database = new Database(temporaryDirectory.resolve("voucher-retry.sqlite"));
        DefenseRepository teams = new DefenseRepository(database);
        ResourceRepository resources = new ResourceRepository(database);
        ResourceVoucherRepository vouchers = new ResourceVoucherRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID outsiderId = UUID.randomUUID();
        teams.createSoloTeam(teamId, ownerId, NOW);
        resources.credit(
                teamId,
                ResourceType.DEFENSE_POINTS,
                4L,
                UUID.randomUUID(),
                "voucher-retry-seed",
                NOW);

        ResourceVoucher voucher = vouchers.withdraw(
                        teamId,
                        ownerId,
                        ResourceType.DEFENSE_POINTS,
                        4L,
                        UUID.randomUUID(),
                        NOW.plusSeconds(1L))
                .voucher();
        UUID delivery = UUID.randomUUID();
        assertEquals(
                VoucherDeliveryOutcome.PREPARED,
                vouchers.prepareDelivery(
                                voucher.voucherId(), ownerId, delivery, NOW.plusSeconds(2L))
                        .outcome());
        assertEquals(
                OperationOutcome.APPLIED,
                vouchers.rollbackDelivery(delivery, NOW.plusSeconds(3L)));
        assertEquals(
                VoucherDeliveryOutcome.PREPARED,
                vouchers.prepareDelivery(
                                voucher.voucherId(), ownerId, delivery, NOW.plusSeconds(4L))
                        .outcome());
        assertEquals(
                OperationOutcome.APPLIED,
                vouchers.applyDelivery(voucher.voucherId(), delivery, NOW.plusSeconds(5L)));

        UUID redeem = UUID.randomUUID();
        assertThrows(
                PersistenceConflictException.class,
                () -> vouchers.prepareRedeem(
                        voucher.voucherId(), outsiderId, UUID.randomUUID(), NOW.plusSeconds(6L)));
        assertEquals(
                OperationOutcome.APPLIED,
                vouchers.prepareRedeem(voucher.voucherId(), ownerId, redeem, NOW.plusSeconds(7L))
                        .outcome());
        assertThrows(
                PersistenceConflictException.class,
                () -> vouchers.prepareRedeem(
                        voucher.voucherId(), ownerId, UUID.randomUUID(), NOW.plusSeconds(8L)));
        assertEquals(
                OperationOutcome.APPLIED,
                vouchers.rollbackRedeem(redeem, NOW.plusSeconds(9L)));
        assertEquals(
                ResourceVoucherState.AVAILABLE,
                vouchers.findVoucher(voucher.voucherId()).orElseThrow().state());
        assertThrows(
                PersistenceConflictException.class,
                () -> vouchers.prepareRedeem(
                        voucher.voucherId(), ownerId, redeem, NOW.plusSeconds(10L)));
        assertThrows(
                PersistenceConflictException.class,
                () -> vouchers.applyRedeem(redeem, NOW.plusSeconds(11L)));
    }
}
