package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.persistence.ResourceVoucherState
import io.github.takenoha.towerdefense.persistence.VoucherRedeemState
import java.util.Objects
import java.util.UUID

/** Pure identity/state rules for recovering a rolled-back voucher redeem. */
class VoucherReceiptRecoveryPolicy private constructor() {
    companion object {
        @JvmStatic
        fun isMatchingRedeemReceipt(
            item: ResourceVoucherItemData?,
            voucherId: UUID,
            operationId: UUID,
        ): Boolean {
            Objects.requireNonNull(voucherId, "voucherId")
            Objects.requireNonNull(operationId, "operationId")
            return item != null &&
                item.voucherId == voucherId &&
                item.redeemOperationId.filter { operationId == it }.isPresent
        }

        @JvmStatic
        fun stripsRolledBackReceipt(
            operationState: VoucherRedeemState,
            voucherState: ResourceVoucherState,
        ): Boolean {
            Objects.requireNonNull(operationState, "operationState")
            Objects.requireNonNull(voucherState, "voucherState")
            return operationState == VoucherRedeemState.ROLLED_BACK &&
                voucherState != ResourceVoucherState.VOIDED
        }
    }
}
