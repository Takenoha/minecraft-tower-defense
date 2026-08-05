package io.github.takenoha.towerdefense.persistence;

import java.util.Objects;

/** Voucher plus its durable delivery operation after a prepare/reconcile read. */
public record VoucherDeliveryResult(
        VoucherDeliveryOutcome outcome,
        ResourceVoucher voucher,
        VoucherDeliveryOperation operation) {
    public VoucherDeliveryResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(voucher, "voucher");
    }
}
