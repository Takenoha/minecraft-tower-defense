package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.persistence.ResourceVoucherState;
import io.github.takenoha.towerdefense.persistence.VoucherRedeemState;
import java.util.Objects;
import java.util.UUID;

/** Pure identity/state rules for recovering a rolled-back voucher redeem. */
public final class VoucherReceiptRecoveryPolicy {
    private VoucherReceiptRecoveryPolicy() {
    }

    public static boolean isMatchingRedeemReceipt(
            ResourceVoucherItemData item,
            UUID voucherId,
            UUID operationId) {
        Objects.requireNonNull(voucherId, "voucherId");
        Objects.requireNonNull(operationId, "operationId");
        return item != null
                && item.voucherId().equals(voucherId)
                && item.redeemOperationId().filter(operationId::equals).isPresent();
    }

    public static boolean stripsRolledBackReceipt(
            VoucherRedeemState operationState,
            ResourceVoucherState voucherState) {
        Objects.requireNonNull(operationState, "operationState");
        Objects.requireNonNull(voucherState, "voucherState");
        return operationState == VoucherRedeemState.ROLLED_BACK
                && voucherState != ResourceVoucherState.VOIDED;
    }
}
