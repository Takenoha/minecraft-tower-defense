package io.github.takenoha.towerdefense.persistence;

import java.util.Objects;

/** Result of a wallet debit plus one durable voucher creation. */
public record VoucherWithdrawalResult(
        OperationOutcome outcome,
        ResourceVoucher voucher) {
    public VoucherWithdrawalResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(voucher, "voucher");
    }
}
