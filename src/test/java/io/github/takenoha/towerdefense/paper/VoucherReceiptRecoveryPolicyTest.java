package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.persistence.ResourceType;
import io.github.takenoha.towerdefense.persistence.ResourceVoucherState;
import io.github.takenoha.towerdefense.persistence.VoucherRedeemState;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VoucherReceiptRecoveryPolicyTest {
    @Test
    void matchesOnlyTheSameVoucherAndRedeemOperation() {
        UUID voucherId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        ResourceVoucherItemData matching = new ResourceVoucherItemData(
                voucherId,
                UUID.randomUUID(),
                ResourceType.DEFENSE_POINTS,
                2L,
                Optional.empty(),
                Optional.of(operationId));

        assertTrue(VoucherReceiptRecoveryPolicy.isMatchingRedeemReceipt(
                matching, voucherId, operationId));
        assertFalse(VoucherReceiptRecoveryPolicy.isMatchingRedeemReceipt(
                matching, UUID.randomUUID(), operationId));
        assertFalse(VoucherReceiptRecoveryPolicy.isMatchingRedeemReceipt(
                matching, voucherId, UUID.randomUUID()));
        assertFalse(VoucherReceiptRecoveryPolicy.isMatchingRedeemReceipt(
                new ResourceVoucherItemData(
                        voucherId,
                        matching.teamId(),
                        matching.resourceType(),
                        matching.quantity(),
                        Optional.of(UUID.randomUUID()),
                        Optional.empty()),
                voucherId,
                operationId));
    }

    @Test
    void rolledBackAvailableVoucherStripsReceiptButVoidInvalidatesCopies() {
        assertTrue(VoucherReceiptRecoveryPolicy.stripsRolledBackReceipt(
                VoucherRedeemState.ROLLED_BACK,
                ResourceVoucherState.AVAILABLE));
        assertFalse(VoucherReceiptRecoveryPolicy.stripsRolledBackReceipt(
                VoucherRedeemState.ROLLED_BACK,
                ResourceVoucherState.VOIDED));
        assertFalse(VoucherReceiptRecoveryPolicy.stripsRolledBackReceipt(
                VoucherRedeemState.PREPARED,
                ResourceVoucherState.AVAILABLE));
    }
}
