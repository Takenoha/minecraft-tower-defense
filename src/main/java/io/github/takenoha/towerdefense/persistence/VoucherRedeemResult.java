package io.github.takenoha.towerdefense.persistence;

import java.util.Objects;

/** Voucher plus its durable redeem operation after a reservation read. */
public record VoucherRedeemResult(
        OperationOutcome outcome,
        ResourceVoucher voucher,
        VoucherRedeemOperation operation) {
    public VoucherRedeemResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(voucher, "voucher");
        Objects.requireNonNull(operation, "operation");
    }
}
